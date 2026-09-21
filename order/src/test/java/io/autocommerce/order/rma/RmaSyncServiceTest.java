package io.autocommerce.order.rma;

import io.autocommerce.core.contract.RmaCapability;
import io.autocommerce.core.order.model.FulfillmentStatus;
import io.autocommerce.core.order.model.OrderModel;
import io.autocommerce.core.order.model.OrderRma;
import io.autocommerce.core.order.model.RmaStatus;
import io.autocommerce.order.model.OrderAggregate;
import io.autocommerce.order.snapshot.OrderAggregateAssembler;
import io.autocommerce.order.store.JsonFileOrderStore;
import io.autocommerce.order.store.OrderStore;
import io.autocommerce.order.testsupport.FixtureSalesPlatformAdapter;
import io.autocommerce.order.testsupport.OrderFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link RmaSyncService}：售后状态只读同步 + 销售履约轴叠加派生（v1 只读，无平台写动作）。 */
class RmaSyncServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T17:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private OrderStore store;

    @BeforeEach
    void seedOrderWithRma() {
        store = new JsonFileOrderStore(tempDir);
        OrderModel base = new OrderAggregateAssembler()
                .assemble(OrderFixtures.order(), OrderFixtures.snapshot());
        store.saveOrderIfAbsent(base);
        OrderAggregate aggregate = OrderAggregate.of(store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow());
        store.updateOrder(aggregate.withRmas(List.of(OrderFixtures.rma())).toDocument());
    }

    @Test
    void terminalRmaRefreshesStatusAndKeepsBaseFulfillment() {
        RmaSyncService service = new RmaSyncService(store, new FixtureSalesPlatformAdapter(), CLOCK);

        List<OrderRma> rmas = service.sync(OrderFixtures.ORDER_ID);

        assertThat(rmas).hasSize(1);
        assertThat(rmas.getFirst().rmaStatus()).isEqualTo(RmaStatus.REFUNDED);
        assertThat(rmas.getFirst().platformStatus()).isEqualTo("REFUND_SUCCESS");
        OrderModel saved = store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow();
        assertThat(saved.rmas().getFirst().rmaStatus()).isEqualTo(RmaStatus.REFUNDED);
        assertThat(saved.orders().getFirst().fulfillmentStatus())
                .as("RMA 已收敛 → 不叠加 REFUNDING，回到派生 base")
                .isEqualTo(FulfillmentStatus.AWAITING_PURCHASE);
    }

    @Test
    void openRmaDerivesRefundingDerivedOnSalesAxis() {
        RmaCapability stillOpen = platformRmaId ->
                new io.autocommerce.core.contract.dto.RmaStatusView(platformRmaId,
                        RmaStatus.WAITING_SELLER, "REFUND_WAIT_SELLER_AGREE");
        RmaSyncService service = new RmaSyncService(store, stillOpen, CLOCK);

        service.sync(OrderFixtures.ORDER_ID);

        assertThat(store.getOrderById(OrderFixtures.ORDER_ID).orElseThrow()
                .orders().getFirst().fulfillmentStatus()).isEqualTo(FulfillmentStatus.REFUNDING);
    }
}
