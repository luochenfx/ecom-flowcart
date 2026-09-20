package io.autocommerce.order.rma;

import io.autocommerce.core.contract.RmaCapability;
import io.autocommerce.core.contract.dto.RmaStatusView;
import io.autocommerce.core.order.model.FulfillmentStatus;
import io.autocommerce.core.order.model.OrderRma;
import io.autocommerce.core.order.model.Timestamps;
import io.autocommerce.order.model.OrderAggregate;
import io.autocommerce.order.status.FulfillmentDeriver;
import io.autocommerce.order.store.OrderStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 售后状态同步（specs/0003 §8；v1 只读）：经 {@link RmaCapability#fetchRmaStatus} 刷新
 * {@link OrderRma} 的 canonical 售后轴，不产生任何写平台动作（同意退款 / 申诉留平台后台人工）。
 *
 * <p>售后单是统一子实体（{@code REFUND | DISPUTE}），销售履约轴的 {@code REFUNDING / DISPUTED}
 * 由未收敛 RMA <b>派生</b>（{@link FulfillmentDeriver#withRmas}），不反向写 RMA。
 * RMA 收敛（终态）时由 activity 侧在落库后广播 {@code rma.closed}——本服务只落库、不发事件。
 */
public final class RmaSyncService {

    private final OrderStore store;
    private final RmaCapability rma;
    private final Clock clock;

    public RmaSyncService(OrderStore store, RmaCapability rma, Clock clock) {
        this.store = Objects.requireNonNull(store, "OrderStore 必填");
        this.rma = Objects.requireNonNull(rma, "RmaCapability 必填");
        this.clock = Objects.requireNonNull(clock, "Clock 必填");
    }

    /** 刷新该订单全部 RMA 的状态；返回刷新后的实体（已终态者供上层发 rma.closed）。 */
    public List<OrderRma> sync(String orderId) {
        OrderAggregate aggregate = OrderAggregate.of(store.getOrderById(orderId)
                .orElseThrow(() -> new IllegalStateException("OrderStore 无此订单聚合: " + orderId)));
        List<OrderRma> updated = new ArrayList<>();
        for (OrderRma rmaEntity : aggregate.rmas()) {
            RmaStatusView view = rma.fetchRmaStatus(rmaEntity.platformRmaId());
            Timestamps timestamps = now();
            updated.add(new OrderRma(rmaEntity.rmaId(), rmaEntity.orderId(), rmaEntity.type(),
                    rmaEntity.platformRmaId(), view.status(), view.platformStatus(), rmaEntity.lines(),
                    rmaEntity.amount(), rmaEntity.reason(), timestamps, rmaEntity.provenance()));
        }
        FulfillmentStatus derived = FulfillmentDeriver.withRmas(
                FulfillmentDeriver.salesFromPurchases(aggregate.purchaseOrders()), updated);
        store.updateOrder(aggregate.withRmas(updated).withFulfillmentStatus(derived).toDocument());
        return List.copyOf(updated);
    }

    private Timestamps now() {
        String iso = clock.instant().toString();
        return new Timestamps(iso, iso);
    }
}
