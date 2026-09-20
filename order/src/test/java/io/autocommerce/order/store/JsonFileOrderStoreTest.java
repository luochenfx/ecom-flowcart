package io.autocommerce.order.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.order.model.ChannelSyncState;
import io.autocommerce.core.order.model.FulfillmentStatus;
import io.autocommerce.core.order.model.OrderModel;
import io.autocommerce.core.order.model.OrderSnapshot;
import io.autocommerce.order.model.OrderAggregate;
import io.autocommerce.order.snapshot.OrderAggregateAssembler;
import io.autocommerce.order.testsupport.OrderFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link JsonFileOrderStore}：幂等写入、聚合抽取、覆盖更新、渠道游标。 */
class JsonFileOrderStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private OrderModel aggregate() {
        OrderModel base = new OrderAggregateAssembler()
                .assemble(OrderFixtures.order(), OrderFixtures.snapshot());
        return OrderAggregate.of(base).withRmas(List.of(OrderFixtures.rma())).toDocument();
    }

    @Test
    void saveIsIdempotentByChannelAndOrderNo() {
        OrderStore store = new JsonFileOrderStore(tempDir);

        OrderStore.SaveOutcome first = store.saveOrderIfAbsent(aggregate());
        OrderStore.SaveOutcome second = store.saveOrderIfAbsent(aggregate());

        assertThat(first.created()).isTrue();
        assertThat(second.created()).as("同 (channel_id, platform_order_no) 重复写入被吸收").isFalse();
        assertThat(store.listOrders()).hasSize(1);
        assertThat(store.findByChannelAndOrderNo(OrderFixtures.CHANNEL_ID, OrderFixtures.PLATFORM_ORDER_NO))
                .isPresent();
        assertThat(store.getOrderById(OrderFixtures.ORDER_ID)).isPresent();
    }

    @Test
    void updateReplacesAggregateAndKeepsChannelCursor() {
        OrderStore store = new JsonFileOrderStore(tempDir);
        store.saveOrderIfAbsent(aggregate());
        store.putChannelSyncState(new ChannelSyncState(OrderFixtures.CHANNEL_ID,
                MAPPER.createObjectNode().put("page", 2), "2026-09-09T14:05:00Z"));

        OrderAggregate loaded = OrderAggregate.of(store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow());
        store.updateOrder(loaded.withFulfillmentStatus(FulfillmentStatus.PURCHASING).toDocument());

        OrderModel reloaded = store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(reloaded.orders().get(0).fulfillmentStatus()).isEqualTo(FulfillmentStatus.PURCHASING);
        assertThat(reloaded.rmas()).hasSize(1);
        assertThat(store.document().channelSyncStates()).hasSize(1);
        assertThat(store.getChannelSyncState(OrderFixtures.CHANNEL_ID).orElseThrow()
                .cursor().get("page").asInt()).isEqualTo(2);
    }

    @Test
    void updateOnMissingOrderFails() {
        OrderStore store = new JsonFileOrderStore(tempDir);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.updateOrder(aggregate()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("目标订单不存在");
    }

    @Test
    void updateRejectsChangedSnapshot() {
        OrderStore store = new JsonFileOrderStore(tempDir);
        OrderModel base = aggregate();
        store.saveOrderIfAbsent(base);

        OrderSnapshot original = OrderFixtures.snapshot();
        OrderSnapshot tampered = new OrderSnapshot(original.snapshotId(), original.orderId(),
                "2099-01-01T00:00:00Z", original.amounts(), original.lineSnapshots(),
                original.shippingAddressMask(), original.platformRaw(), original.provenance());
        OrderModel incoming = new OrderModel(base.schemaVersion(), base.orders(), base.orderLines(),
                List.of(tampered), base.purchaseOrders(), base.rmas(), base.channelSyncStates());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.updateOrder(incoming))
                .as("specs/0003 §3 铁律：既有快照与入参不一致即拒绝覆写")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OrderSnapshot 不可变");
    }
}
