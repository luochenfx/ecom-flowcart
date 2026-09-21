package io.autocommerce.order.purchase;

import io.autocommerce.core.order.model.FulfillmentStatus;
import io.autocommerce.core.order.model.OrderModel;
import io.autocommerce.core.order.model.PurchaseOrder;
import io.autocommerce.core.order.model.PurchaseStatus;
import io.autocommerce.core.order.model.ShippingAddress;
import io.autocommerce.core.order.model.ShippingAddressState;
import io.autocommerce.order.address.AddressCipher;
import io.autocommerce.order.model.OrderAggregate;
import io.autocommerce.order.snapshot.OrderAggregateAssembler;
import io.autocommerce.order.store.JsonFileOrderStore;
import io.autocommerce.order.store.OrderStore;
import io.autocommerce.order.testsupport.FixtureSalesPlatformAdapter;
import io.autocommerce.order.testsupport.FixtureSourcePlatformAdapter;
import io.autocommerce.order.testsupport.OrderFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PurchaseFulfillmentService}：Order → 1:N 跨供应商拆单 / 下采购单前解密地址 /
 * 发货回传销售平台 / 双轴状态推进。以 fixture 假销售侧 + 货源侧 Adapter 驱动（不 mock 业务逻辑）。
 */
class PurchaseFulfillmentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T16:00:00Z"), ZoneOffset.UTC);
    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private OrderStore store;
    private FixtureSalesPlatformAdapter sales;
    private FixtureSourcePlatformAdapter source;
    private AddressCipher cipher;
    private PurchaseFulfillmentService service;

    @BeforeEach
    void setUp() {
        store = new JsonFileOrderStore(tempDir);
        sales = new FixtureSalesPlatformAdapter();
        source = new FixtureSourcePlatformAdapter();
        cipher = new AddressCipher(KEY);
        service = new PurchaseFulfillmentService(store, source, sales, sales, cipher,
                new PurchasePlanner(OrderFixtures.sourcingResolver()), CLOCK);
        store.saveOrderIfAbsent(new OrderAggregateAssembler()
                .assemble(OrderFixtures.order(), OrderFixtures.snapshot()));
    }

    @Test
    void crossSupplierOrderSplitsIntoOnePurchaseOrderPerSupplier() {
        List<PurchaseOrder> created = service.placePurchases(OrderFixtures.ORDER_ID);

        assertThat(created).hasSize(2);
        assertThat(created).extracting(p -> p.supplierRef().supplierId())
                .containsExactlyInAnyOrder(OrderFixtures.SUPPLIER_ID_1, OrderFixtures.SUPPLIER_ID_2);
        assertThat(created).allMatch(p -> p.purchaseStatus() == PurchaseStatus.PAID);
        assertThat(source.drafts()).as("一销售单拆两张采购单：各下一单").hasSize(2);
        assertThat(source.paid()).hasSize(2);

        OrderModel saved = store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(saved.purchaseOrders()).hasSize(2);
        assertThat(saved.orderLines()).as("订单行回填采购行引用（1:1 拆单）")
                .allMatch(l -> l.purchaseLineRefs().size() == 1);
    }

    @Test
    void addressDecryptedOnlyAtPurchaseTime() {
        ShippingAddress before = store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow()
                .orders().getFirst().shippingAddress();
        assertThat(before.state()).isEqualTo(ShippingAddressState.MASKED);
        assertThat(sales.decryptRequests()).isEmpty();

        service.placePurchases(OrderFixtures.ORDER_ID);

        assertThat(sales.decryptRequests()).as("下采购单前才触发一次解密").hasSize(1);
        ShippingAddress after = store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow()
                .orders().getFirst().shippingAddress();
        assertThat(after.state()).isEqualTo(ShippingAddressState.DECRYPTED);
        assertThat(after.encryptedPayload()).isNotBlank();
        assertThat(cipher.decrypt(after.encryptedPayload())).isEqualTo(OrderFixtures.decryptedAddress());
    }

    @Test
    void placePurchasesIsIdempotent() {
        service.placePurchases(OrderFixtures.ORDER_ID);
        service.placePurchases(OrderFixtures.ORDER_ID);

        assertThat(source.drafts()).as("已有采购单不重复下单（花钱的外部副作用）").hasSize(2);
        assertThat(store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow().purchaseOrders()).hasSize(2);
    }

    @Test
    void planningFailureDoesNotLeakAddressDecrypt() {
        PurchaseFulfillmentService noSourcing = new PurchaseFulfillmentService(store, source, sales, sales,
                cipher, new PurchasePlanner(SourcingRefResolver.none()), CLOCK);

        // 缺货源映射 = 装配/数据错误（不静默漏采购）；且解密发生在规划之后 → 规划失败绝不泄漏解密
        assertThatThrownBy(() -> noSourcing.placePurchases(OrderFixtures.ORDER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺货源映射");
        assertThat(sales.decryptRequests()).isEmpty();
    }

    @Test
    void returnShipmentsNotifiesSalesChannelAndAdvancesAxes() {
        service.placePurchases(OrderFixtures.ORDER_ID);

        List<PurchaseOrder> shipped = service.returnShipments(OrderFixtures.ORDER_ID);

        assertThat(shipped).allMatch(p -> p.purchaseStatus() == PurchaseStatus.SHIPPED);
        assertThat(shipped).allMatch(p -> !p.tracking().isEmpty());
        assertThat(sales.notifications()).as("每张采购单回传一次发货").hasSize(2);
        assertThat(sales.notifications().getFirst().platformOrderNo()).isEqualTo(OrderFixtures.PLATFORM_ORDER_NO);
        assertThat(source.logisticsQueries()).hasSize(2);

        OrderModel saved = store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(saved.orders().getFirst().fulfillmentStatus()).as("采购全部发货 → 销售侧 SHIPPED")
                .isEqualTo(FulfillmentStatus.SHIPPED);
    }

    @Test
    void shipmentReturnDoesNotOverrideOpenRmaSalesAxis() {
        // 该订单已挂 open RMA（WAITING_SELLER）——销售轴应叠加派生为 REFUNDING
        store.updateOrder(OrderAggregate.of(store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow())
                .withRmas(List.of(OrderFixtures.rma())).toDocument());
        service.placePurchases(OrderFixtures.ORDER_ID);
        store.updateOrder(OrderAggregate.of(store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow())
                .withFulfillmentStatus(FulfillmentStatus.REFUNDING).toDocument());

        service.returnShipments(OrderFixtures.ORDER_ID);

        assertThat(store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow()
                .orders().getFirst().fulfillmentStatus())
                .as("有 open RMA 时发货回传不得把销售轴从 REFUNDING 覆盖回 SHIPPED（单一派生入口）")
                .isEqualTo(FulfillmentStatus.REFUNDING);
    }
}
