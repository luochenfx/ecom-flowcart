package io.autocommerce.worker.order;

import io.autocommerce.core.contract.AddressCapability;
import io.autocommerce.core.contract.OrderSyncCapability;
import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.contract.PurchaseCapability;
import io.autocommerce.core.contract.ShipmentCapability;
import io.autocommerce.core.message.Envelope;
import io.autocommerce.core.message.EventTypes;
import io.autocommerce.core.order.model.OrderModel;
import io.autocommerce.core.order.model.PurchaseStatus;
import io.autocommerce.core.order.model.ShippingAddressState;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractSchemas;
import io.autocommerce.order.address.AddressCipher;
import io.autocommerce.order.purchase.PurchaseFulfillmentService;
import io.autocommerce.order.purchase.PurchasePlanner;
import io.autocommerce.order.store.JsonFileOrderStore;
import io.autocommerce.order.sync.OrderSyncService;
import io.autocommerce.order.testsupport.FixtureSalesPlatformAdapter;
import io.autocommerce.order.testsupport.FixtureSourcePlatformAdapter;
import io.autocommerce.order.testsupport.OrderFixtures;
import io.autocommerce.worker.event.NoopEventPublisher;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采购单链端到端（#22 AC-4：「采购单自有状态轴 / workflow」）：单张采购单经<b>自己的确定性 workflowId</b>
 * {@code purchase-{orderId}-{supplierId}} 独立驱动「解密地址 → 下单 + 支付 → 发货回传」，不依赖订单链。
 *
 * <p>真实域服务 + 真实 Temporal workflow（in-process test service），只有外部平台是 fixture 假 adapter。
 */
class PurchaseWorkflowE2ETest {

    private static final Clock CLOCK = Clock.fixed(java.time.Instant.parse("2026-09-09T19:00:00Z"),
            java.time.ZoneOffset.UTC);
    private static final byte[] AES_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private FixtureSalesPlatformAdapter sales;
    private FixtureSourcePlatformAdapter source;
    private JsonFileOrderStore store;
    private NoopEventPublisher events;
    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        sales = provider(FixtureSalesPlatformAdapter.PLATFORM, FixtureSalesPlatformAdapter.class);
        source = provider(FixtureSourcePlatformAdapter.PLATFORM, FixtureSourcePlatformAdapter.class);
        store = new JsonFileOrderStore(tempDir.resolve("order"));
        events = new NoopEventPublisher();
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    @Test
    void purchaseWorkflowRunsStandaloneAndIsIdempotent() {
        // 订单入（拉取 = 真相）：只落库建单，不跑订单链
        OrderSyncService sync = new OrderSyncService(OrderFixtures.CHANNEL_ID,
                sales.getCapability(OrderSyncCapability.class), store, OrderFixtures.rmaSource(), CLOCK);
        assertThat(sync.pull().createdOrderIds()).containsExactly(OrderFixtures.ORDER_ID);

        PurchaseActivities purchaseActivities = new PurchaseActivitiesImpl(fulfillment(), events,
                "PurchaseWorkflow");
        env = TestWorkflowEnvironment.newInstance();
        env.newWorker(OrderRuntime.TASK_QUEUE).registerWorkflowImplementationTypes(PurchaseWorkflowImpl.class);
        env.newWorker(OrderRuntime.TASK_QUEUE).registerActivitiesImplementations(purchaseActivities);
        env.start();

        PurchaseWorkflowInput input = new PurchaseWorkflowInput(OrderFixtures.ORDER_ID,
                OrderFixtures.SUPPLIER_ID_1);
        PurchaseWorkflowResult result = launcher().run(input);

        // —— 采购单自有 workflowId（= purchase_order_id，同源）——
        assertThat(result.purchaseOrderId())
                .isEqualTo(PurchaseFulfillmentService.purchaseOrderId(OrderFixtures.ORDER_ID,
                        OrderFixtures.SUPPLIER_ID_1));
        assertThat(env.getWorkflowClient().fetchHistory(PurchaseRuntime.workflowIdFor(
                OrderFixtures.ORDER_ID, OrderFixtures.SUPPLIER_ID_1))
                .getHistory().getEventsList().get(0)
                .getWorkflowExecutionStartedEventAttributes().getWorkflowType().getName())
                .isEqualTo("PurchaseWorkflow");

        // —— 采购轴收敛 + 地址解密（下单前）+ 发货回传销售平台 ——
        assertThat(result.purchaseStatus()).isEqualTo(PurchaseStatus.SHIPPED);
        OrderModel doc = store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(doc.purchaseOrders()).hasSize(1);
        assertThat(doc.purchaseOrders().get(0).purchaseStatus()).isEqualTo(PurchaseStatus.SHIPPED);
        assertThat(doc.orders().get(0).shippingAddress().state()).isEqualTo(ShippingAddressState.DECRYPTED);
        assertThat(sales.notifications()).hasSize(1);

        // —— 事件广播：落库后 purchase.shipped，payload 过 message schema 契约门 ——
        assertThat(events.publishedDomainEvents()).hasSize(1);
        Envelope event = events.publishedDomainEvents().get(0);
        assertThat(event.type()).isEqualTo(EventTypes.PURCHASE_SHIPPED);
        ContractAssertions.assertValid(ContractSchemas.payloadFor(event.type()), event.payload(),
                "事件 payload: " + event.type());

        // —— completed → 重复触发不产生新 run、不重复下单 / 回传 ——
        int notificationsBefore = sales.notifications().size();
        int draftsBefore = source.drafts().size();
        PurchaseWorkflowResult repeated = launcher().run(input);
        assertThat(repeated.purchaseOrderId()).isEqualTo(result.purchaseOrderId());
        assertThat(sales.notifications()).hasSize(notificationsBefore);
        assertThat(source.drafts()).hasSize(draftsBefore);
    }

    private PurchaseFulfillmentService fulfillment() {
        return new PurchaseFulfillmentService(store, source.getCapability(PurchaseCapability.class),
                sales.getCapability(ShipmentCapability.class), sales.getCapability(AddressCapability.class),
                new AddressCipher(AES_KEY), new PurchasePlanner(OrderFixtures.sourcingResolver()), CLOCK);
    }

    private PurchaseWorkflowLauncher launcher() {
        return new PurchaseWorkflowLauncher(env.getWorkflowClient(), OrderRuntime.TASK_QUEUE);
    }

    private static <T extends PlatformAdapterProvider> T provider(String platform, Class<T> type) {
        return ServiceLoader.load(PlatformAdapterProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> platform.equals(p.platform()))
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("classpath 未发现 fixture adapter: " + platform
                        + "（order test-jar 是否在 test classpath?）"));
    }
}
