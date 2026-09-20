package io.autocommerce.order.sync;

import io.autocommerce.core.contract.OrderSyncCapability;
import io.autocommerce.core.contract.dto.OrderSyncPage;
import io.autocommerce.core.order.model.Amounts;
import io.autocommerce.core.order.model.Money;
import io.autocommerce.core.order.model.OrderModel;
import io.autocommerce.core.order.model.OrderSnapshot;
import io.autocommerce.order.store.JsonFileOrderStore;
import io.autocommerce.order.store.OrderStore;
import io.autocommerce.order.testsupport.FixtureSalesPlatformAdapter;
import io.autocommerce.order.testsupport.OrderFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link OrderSyncService}：拉取=真相 + 游标落库 + 两级幂等 + 快照不可变 + webhook 仅唤醒。 */
class OrderSyncServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T15:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private OrderStore store() {
        return new JsonFileOrderStore(tempDir);
    }

    private OrderSyncService service(OrderStore store, OrderSyncCapability capability) {
        return new OrderSyncService(OrderFixtures.CHANNEL_ID, capability, store,
                OrderFixtures.rmaSource(), CLOCK);
    }

    @Test
    void pullPersistsOrderSnapshotAndCursor() {
        OrderStore store = store();
        OrderSyncService.SyncResult result = service(store, new FixtureSalesPlatformAdapter()).pull();

        assertThat(result.createdOrderIds()).containsExactly(OrderFixtures.ORDER_ID);
        assertThat(result.existingOrderIds()).isEmpty();

        OrderModel saved = store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(saved.orderSnapshots()).hasSize(1);
        assertThat(saved.orders().get(0).provenance().createdByStep().name()).isEqualTo("CAPTURE");
        assertThat(saved.orderLines()).hasSize(2);
        assertThat(saved.rmas()).hasSize(1);
        // 游标落库（不进 Temporal）且已推进
        assertThat(store.getChannelSyncState(OrderFixtures.CHANNEL_ID)).isPresent();
        assertThat(store.getChannelSyncState(OrderFixtures.CHANNEL_ID).orElseThrow()
                .cursor().get("page").asInt()).isEqualTo(1);
    }

    @Test
    void duplicatePullDoesNotCreateDuplicateOrder() {
        OrderStore store = store();
        OrderSyncService service = service(store, new FixtureSalesPlatformAdapter(true));

        service.pull();
        OrderSyncService.SyncResult second = service.pull();

        assertThat(second.createdOrderIds()).isEmpty();
        assertThat(second.existingOrderIds()).containsExactly(OrderFixtures.ORDER_ID);
        assertThat(store.listOrders()).as("重复拉取不产生重复单（两级幂等）").hasSize(1);
    }

    @Test
    void resyncWithChangedPriceDoesNotOverwriteSnapshot() {
        OrderStore store = store();
        Deque<OrderSyncPage> pages = new ArrayDeque<>(List.of(OrderFixtures.page(), changedPricePage()));
        OrderSyncCapability capability = cursor ->
                pages.isEmpty() ? OrderFixtures.emptyPage() : pages.poll();

        OrderSyncService service = service(store, capability);
        service.pull();
        service.pull();

        OrderSnapshot snapshot = store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow()
                .orderSnapshots().get(0);
        assertThat(snapshot.amounts().paymentAmount().amount())
                .as("建单快照固化后，平台改价不影响历史订单")
                .isEqualByComparingTo(new BigDecimal("148.00"));
    }

    @Test
    void webhookTestSignatureAcceptsSignedRejectsTampered() {
        OrderWebhookVerifier verifier = new OrderWebhookVerifier("fixture-secret");
        String body = "{\"channel_id\":\"fixture-shop-a\",\"order_no\":\"202609090001\"}";

        verifier.verify(body, verifier.sign(body));

        assertThatThrownBy(() -> verifier.verify(body, "deadbeef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验签失败");
        assertThatThrownBy(() -> verifier.verify(body, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少签名");
    }

    /** 第二页：同订单但实付价被平台改动（用于证明快照不被覆写）。 */
    private static OrderSyncPage changedPricePage() {
        OrderSnapshot s = OrderFixtures.snapshot();
        OrderSnapshot changed = new OrderSnapshot(s.snapshotId(), s.orderId(), s.capturedAt(),
                new Amounts(s.amounts().goodsAmount(), s.amounts().shippingAmount(),
                        s.amounts().discountAmount(),
                        new Money(new BigDecimal("999.00"), "CNY"), "CNY"),
                s.lineSnapshots(), s.shippingAddressMask(), s.platformRaw(), s.provenance());
        return new OrderSyncPage(List.of(OrderFixtures.order()), List.of(changed), null);
    }
}
