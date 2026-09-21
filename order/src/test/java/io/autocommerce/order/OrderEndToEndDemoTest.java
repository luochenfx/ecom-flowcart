package io.autocommerce.order;

import io.autocommerce.core.contract.AddressCapability;
import io.autocommerce.core.contract.OrderSyncCapability;
import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.contract.PurchaseCapability;
import io.autocommerce.core.contract.RmaCapability;
import io.autocommerce.core.contract.ShipmentCapability;
import io.autocommerce.core.order.model.FulfillmentStatus;
import io.autocommerce.core.order.model.OrderModel;
import io.autocommerce.core.order.model.PurchaseOrder;
import io.autocommerce.core.order.model.PurchaseStatus;
import io.autocommerce.core.order.model.RmaStatus;
import io.autocommerce.core.order.model.ShippingAddressState;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractObjectMapper;
import io.autocommerce.core.testutil.ContractSchemas;
import io.autocommerce.order.address.AddressCipher;
import io.autocommerce.order.purchase.PurchaseFulfillmentService;
import io.autocommerce.order.purchase.PurchasePlanner;
import io.autocommerce.order.rma.RmaSyncService;
import io.autocommerce.order.store.JsonFileOrderStore;
import io.autocommerce.order.sync.OrderSyncService;
import io.autocommerce.order.sync.OrderWebhookVerifier;
import io.autocommerce.order.testsupport.FixtureSalesPlatformAdapter;
import io.autocommerce.order.testsupport.FixtureSourcePlatformAdapter;
import io.autocommerce.order.testsupport.OrderFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单域全链 demo（#22 AC 末条）：<b>订单入 → 建单快照 → 拆采购单（1:N 跨供应商）→ 发货回传 → RMA 读</b>。
 *
 * <p>装配口径与 #19 / #20 的 demo 一致：fixture 假 OrderSync / Purchase（+ Shipment / Rma / Address）
 * adapter 经 <b>Java SPI</b> 装配（无中央注册表）→ 真实域服务跑 → {@link JsonFileOrderStore} 落盘可见 →
 * 契约门（{@link ContractSchemas#order()}）+ 事件与落库要素断言。
 *
 * <p>刻意<b>不 mock 业务逻辑</b>：拆单 / 解密地址 / 状态派生 / 幂等吸收都是真实实现，只有外部平台是假的。
 */
class OrderEndToEndDemoTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T18:00:00Z"), ZoneOffset.UTC);
    private static final byte[] AES_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final Path DEMO_ROOT = Path.of("target", "order-demo");

    @Test
    void fullChainFromOrderSyncToShipmentReturn() throws Exception {
        // demo 输出目录每次清空（重复执行幂等；落库文档供人工检视）
        Files.deleteIfExists(DEMO_ROOT.resolve("orders.json"));

        // —— SPI 装配（无中央注册表）：classpath 发现 fixture 假 adapter ——
        FixtureSalesPlatformAdapter sales = provider(FixtureSalesPlatformAdapter.PLATFORM,
                FixtureSalesPlatformAdapter.class);
        FixtureSourcePlatformAdapter source = provider(FixtureSourcePlatformAdapter.PLATFORM,
                FixtureSourcePlatformAdapter.class);
        OrderSyncCapability orderSync = sales.getCapability(OrderSyncCapability.class);
        PurchaseCapability purchase = source.getCapability(PurchaseCapability.class);
        ShipmentCapability shipment = sales.getCapability(ShipmentCapability.class);
        RmaCapability rma = sales.getCapability(RmaCapability.class);
        AddressCapability address = sales.getCapability(AddressCapability.class);

        JsonFileOrderStore store = new JsonFileOrderStore(DEMO_ROOT);

        // 1) 拉取 = 真相：拉取落库 + 建单快照 + 游标落库
        OrderSyncService sync = new OrderSyncService(OrderFixtures.CHANNEL_ID, orderSync, store,
                OrderFixtures.rmaSource(), CLOCK);
        OrderSyncService.SyncResult syncResult = sync.pullAll();
        assertThat(syncResult.createdOrderIds()).containsExactly(OrderFixtures.ORDER_ID);

        // 2) 拆采购单（1:N 跨供应商）+ 下采购单前解密地址 + 下单/支付
        AddressCipher cipher = new AddressCipher(AES_KEY);
        PurchaseFulfillmentService fulfillment = new PurchaseFulfillmentService(store, purchase, shipment,
                address, cipher, new PurchasePlanner(OrderFixtures.sourcingResolver()), CLOCK);
        List<PurchaseOrder> created = fulfillment.placePurchases(OrderFixtures.ORDER_ID);
        assertThat(created).as("两供应商 → 两张采购单").hasSize(2);

        // 3) 发货回传：供应商物流 → 销售平台（顾客可追踪）+ 双轴推进
        List<PurchaseOrder> shipped = fulfillment.returnShipments(OrderFixtures.ORDER_ID);
        assertThat(shipped).allMatch(p -> p.purchaseStatus() == PurchaseStatus.SHIPPED);
        assertThat(sales.notifications()).hasSize(2);

        // 4) RMA 读（v1 只读）：刷新售后状态（终态）
        new RmaSyncService(store, rma, CLOCK).sync(OrderFixtures.ORDER_ID);

        // —— 落库要素 ——
        OrderModel doc = store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(doc.orderSnapshots()).as("建单快照固化").hasSize(1);
        assertThat(doc.orderLines()).hasSize(2);
        assertThat(doc.orderLines()).allMatch(l -> l.purchaseLineRefs().size() == 1);
        assertThat(doc.purchaseOrders()).hasSize(2);
        assertThat(doc.orders().getFirst().shippingAddress().state())
                .isEqualTo(ShippingAddressState.DECRYPTED);
        assertThat(cipher.decrypt(doc.orders().getFirst().shippingAddress().encryptedPayload()))
                .isEqualTo(OrderFixtures.decryptedAddress());
        assertThat(doc.rmas().getFirst().rmaStatus()).isEqualTo(RmaStatus.REFUNDED);
        assertThat(doc.orders().getFirst().fulfillmentStatus()).isEqualTo(FulfillmentStatus.SHIPPED);

        // —— 两级幂等：重复拉取（等价 webhook 重推）不产生重复单 ——
        OrderWebhookVerifier verifier = new OrderWebhookVerifier("fixture-secret");
        String webhookBody = "{\"channel_id\":\"fixture-shop-a\"}";
        verifier.verify(webhookBody, verifier.sign(webhookBody)); // 验签通过 → 唤醒
        OrderSyncService redundant = new OrderSyncService(OrderFixtures.CHANNEL_ID,
                new FixtureSalesPlatformAdapter(true), store, OrderFixtures.rmaSource(), CLOCK);
        OrderSyncService.SyncResult repeated = redundant.pull();
        assertThat(repeated.existingOrderIds()).containsExactly(OrderFixtures.ORDER_ID);
        assertThat(store.listOrders()).as("重复事件不产生重复单").hasSize(1);

        // —— 契约门：全量订单文档过 order schema（Testing §1）——
        ContractAssertions.assertValid(ContractSchemas.order(),
                ContractObjectMapper.create().valueToTree(store.document()), "order demo 文档");

        System.out.println("[demo] 订单已落库: target/order-demo/orders.json"
                + "（采购单=" + doc.purchaseOrders().size() + ", 发货回传=" + sales.notifications().size()
                + ", RMA=" + doc.rmas().size() + "）");
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
