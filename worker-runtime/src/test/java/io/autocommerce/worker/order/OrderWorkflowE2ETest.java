package io.autocommerce.worker.order;

import io.autocommerce.core.contract.AddressCapability;
import io.autocommerce.core.contract.OrderSyncCapability;
import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.contract.PurchaseCapability;
import io.autocommerce.core.contract.RmaCapability;
import io.autocommerce.core.contract.ShipmentCapability;
import io.autocommerce.core.message.Envelope;
import io.autocommerce.core.message.EventTypes;
import io.autocommerce.core.order.model.FulfillmentStatus;
import io.autocommerce.core.order.model.OrderModel;
import io.autocommerce.core.order.model.PurchaseStatus;
import io.autocommerce.core.order.model.RmaStatus;
import io.autocommerce.core.order.model.ShippingAddressState;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractSchemas;
import io.autocommerce.order.address.AddressCipher;
import io.autocommerce.order.purchase.PurchaseFulfillmentService;
import io.autocommerce.order.purchase.PurchasePlanner;
import io.autocommerce.order.rma.RmaSyncService;
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
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单链端到端 demo（#22 AC 末条 + AC-1/AC-2/AC-4/AC-5/AC-6/AC-7/AC-8）：**订单入 → 建单快照 →
 * 拆采购单（1:N）→ 发货回传 → RMA 读 → 事件广播**全链，经 fixture 假 adapter 驱动、真实域服务 + 真实
 * Temporal workflow 跑（in-process test service，无需 docker / CLI）。
 *
 * <p>刻意不 mock 业务逻辑：OrderSyncService / PurchasePlanner / PurchaseFulfillmentService /
 * RmaSyncService / OrderWorkflowImpl / JsonFileOrderStore 都是真实实现，只有外部平台是假的。
 */
class OrderWorkflowE2ETest {

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
    void orderChainRunsEndToEnd() {
        // 订单入（拉取 = 真相）：落库 + 建单快照 + 游标
        OrderSyncService sync = new OrderSyncService(OrderFixtures.CHANNEL_ID,
                sales.getCapability(OrderSyncCapability.class), store, OrderFixtures.rmaSource(), CLOCK);
        assertThat(sync.pull().createdOrderIds()).containsExactly(OrderFixtures.ORDER_ID);

        // 真实域服务 + 真实 Temporal workflow（in-process test service）
        OrderActivities activities = new OrderActivitiesImpl(store, fulfillment(), rmaSync(), events,
                "OrderWorkflow");
        PurchaseActivities purchaseActivities = new PurchaseActivitiesImpl(fulfillment(), events,
                "PurchaseWorkflow");
        env = TestWorkflowEnvironment.newInstance();
        OrderWorkerFactory.register(env.newWorker(OrderRuntime.TASK_QUEUE), activities, purchaseActivities);
        env.start();

        OrderWorkflowInput input = new OrderWorkflowInput(OrderFixtures.ORDER_ID,
                OrderFixtures.PLATFORM, OrderFixtures.PLATFORM_ORDER_NO);
        OrderWorkflowResult result = launcher().run(input);

        // —— 链路收敛 ——
        assertThat(result.fulfillmentStatus()).isEqualTo(FulfillmentStatus.SHIPPED);
        assertThat(result.purchaseOrderIds()).as("两供应商 → 两张采购单（1:N 拆单）").hasSize(2);
        assertThat(result.rmaIds()).containsExactly(OrderFixtures.RMA_ID);

        // —— 落库要素 ——
        OrderModel doc = store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(doc.orderSnapshots()).hasSize(1);
        assertThat(doc.purchaseOrders()).hasSize(2);
        assertThat(doc.purchaseOrders()).allMatch(p -> p.purchaseStatus() == PurchaseStatus.SHIPPED);
        assertThat(doc.purchaseOrders()).allMatch(p -> !p.tracking().isEmpty());
        assertThat(doc.orders().getFirst().shippingAddress().state()).isEqualTo(ShippingAddressState.DECRYPTED);
        assertThat(doc.rmas().getFirst().rmaStatus()).isEqualTo(RmaStatus.REFUNDED);
        assertThat(sales.notifications()).as("发货回传销售平台（顾客可追踪）").hasSize(2);

        // —— 事件广播：均在落库后，payload 过 message schema 契约门 ——
        List<String> types = events.publishedDomainEvents().stream().map(Envelope::type).toList();
        assertThat(types).contains(EventTypes.ORDER_PAID);
        assertThat(types).filteredOn(EventTypes.PURCHASE_SHIPPED::equals).hasSize(2);
        assertThat(types).contains(EventTypes.RMA_CLOSED);
        for (Envelope envelope : events.publishedDomainEvents()) {
            ContractAssertions.assertValid(ContractSchemas.payloadFor(envelope.type()), envelope.payload(),
                    "事件 payload: " + envelope.type());
        }

        // —— 确定性 workflowId 生效（specs/0003 §6 幂等第一级）——
        assertThat(env.getWorkflowClient()
                .fetchHistory(OrderRuntime.workflowIdFor(OrderFixtures.PLATFORM,
                        OrderFixtures.PLATFORM_ORDER_NO))
                .getWorkflowExecution().getWorkflowId())
                .isEqualTo(OrderFixtures.ORDER_ID);

        // —— 采购单自有 workflow（AC-4）：每供应商一个 purchase-{orderId}-{supplierId} execution，
        //    与 PurchaseOrder.purchase_order_id 同源 ——
        assertThat(result.purchaseOrderIds()).containsExactlyInAnyOrder(
                PurchaseFulfillmentService.purchaseOrderId(OrderFixtures.ORDER_ID, OrderFixtures.SUPPLIER_ID_1),
                PurchaseFulfillmentService.purchaseOrderId(OrderFixtures.ORDER_ID, OrderFixtures.SUPPLIER_ID_2));
        for (String supplierId : List.of(OrderFixtures.SUPPLIER_ID_1, OrderFixtures.SUPPLIER_ID_2)) {
            String childId = PurchaseRuntime.workflowIdFor(OrderFixtures.ORDER_ID, supplierId);
            assertThat(env.getWorkflowClient().fetchHistory(childId)
                    .getHistory().getEventsList().getFirst()
                    .getWorkflowExecutionStartedEventAttributes().getWorkflowType().getName())
                    .as("采购单自有 workflow execution: " + childId)
                    .isEqualTo("PurchaseWorkflow");
        }

        // —— 前一次 completed → 重复触发不产生新 run、不重复下单 / 回传（ADR-0003）——
        int notificationsBefore = sales.notifications().size();
        int draftsBefore = source.drafts().size();
        List<String> eventIdsBeforeRepeat = events.publishedDomainEvents().stream()
                .map(Envelope::id).toList();
        OrderWorkflowResult repeated = launcher().run(input);
        assertThat(repeated.purchaseOrderIds()).isEqualTo(result.purchaseOrderIds());
        assertThat(sales.notifications()).as("completed 后重复触发不得再次回传发货")
                .hasSize(notificationsBefore);
        assertThat(source.drafts()).as("completed 后重复触发不得再次下单").hasSize(draftsBefore);
        assertThat(store.listOrders()).hasSize(1);
        assertThat(events.publishedDomainEvents().stream().map(Envelope::id).toList())
                .as("completed 后重复触发（activity 重跑）不得新增事件信封——同一事实仍得同一幂等锚")
                .isEqualTo(eventIdsBeforeRepeat);
    }

    private PurchaseFulfillmentService fulfillment() {
        return new PurchaseFulfillmentService(store, source.getCapability(PurchaseCapability.class),
                sales.getCapability(ShipmentCapability.class), sales.getCapability(AddressCapability.class),
                new AddressCipher(AES_KEY), new PurchasePlanner(OrderFixtures.sourcingResolver()), CLOCK);
    }

    private RmaSyncService rmaSync() {
        return new RmaSyncService(store, sales.getCapability(RmaCapability.class), CLOCK);
    }

    private OrderWorkflowLauncher launcher() {
        return new OrderWorkflowLauncher(env.getWorkflowClient(), OrderRuntime.TASK_QUEUE);
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
