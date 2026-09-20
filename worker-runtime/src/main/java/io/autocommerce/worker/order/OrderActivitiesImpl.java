package io.autocommerce.worker.order;

import io.autocommerce.core.message.RmaOutcome;
import io.autocommerce.core.order.model.Amounts;
import io.autocommerce.core.order.model.Order;
import io.autocommerce.core.order.model.OrderRma;
import io.autocommerce.core.order.model.OrderSnapshot;
import io.autocommerce.core.order.model.PurchaseOrder;
import io.autocommerce.order.model.OrderAggregate;
import io.autocommerce.order.purchase.PurchaseFulfillmentService;
import io.autocommerce.order.rma.RmaSyncService;
import io.autocommerce.order.status.FulfillmentDeriver;
import io.autocommerce.order.store.OrderStore;
import io.autocommerce.worker.event.DomainEvents;
import io.autocommerce.worker.event.EventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 订单链 activity 实现：**落库 + 外部副作用**，Domain Event 一律在落库之后广播（specs/0016 §0.3）。
 *
 * <p>业务逻辑不在此处——订单聚合读 / 拆单计划 / RMA 同步都在 order 模块的服务里
 * （{@link PurchaseFulfillmentService} / {@link RmaSyncService}）。本类只做三件事：读聚合、调服务、
 * 落库后发事件。故它是可被测的薄壳，且业务模块保持零 Temporal 依赖（禁环②）。
 *
 * <p>采购下单 / 支付 / 发货回传不在本类：它们是<b>采购单自有 workflow</b>
 * （{@link PurchaseWorkflow} / {@link PurchaseActivitiesImpl}）的职责——订单链只负责为每个供应商起一个
 * child workflow，并回读销售履约轴的派生结果。
 *
 * <p><b>事件 A-prime 降级</b>：落库后、发事件前 crash → 事件丢失；消费端以业务库回读为准、
 * envelope.id 作幂等锚（重复广播由消费端去重）。事务基建（落库与发事件同事务）不在本 slice。
 */
public final class OrderActivitiesImpl implements OrderActivities {

    private final OrderStore store;
    private final PurchaseFulfillmentService fulfillment;
    private final RmaSyncService rmaSync;
    private final EventPublisher events;
    private final String workflowType;

    public OrderActivitiesImpl(OrderStore store, PurchaseFulfillmentService fulfillment,
                               RmaSyncService rmaSync, EventPublisher events, String workflowType) {
        this.store = Objects.requireNonNull(store, "OrderStore 必填");
        this.fulfillment = Objects.requireNonNull(fulfillment, "PurchaseFulfillmentService 必填");
        this.rmaSync = Objects.requireNonNull(rmaSync, "RmaSyncService 必填");
        this.events = Objects.requireNonNull(events, "EventPublisher 必填");
        this.workflowType = Objects.requireNonNull(workflowType, "workflowType 必填");
    }

    @Override
    public OrderView prepare(OrderWorkflowInput input) {
        OrderAggregate aggregate = load(input.orderId());
        Order order = aggregate.order();
        boolean paid = FulfillmentDeriver.isPaid(order.fulfillmentStatus());
        BigDecimal amount = null;
        String currency = null;
        String paidAt = null;
        if (paid) {
            OrderSnapshot snapshot = aggregate.snapshot();
            Amounts amounts = snapshot.amounts();
            if (amounts != null && amounts.paymentAmount() != null) {
                amount = amounts.paymentAmount().amount();
                currency = amounts.paymentAmount().currency();
            }
            paidAt = order.platformStatusTime() != null
                    ? order.platformStatusTime() : snapshot.capturedAt();
            if (amount != null && currency != null && paidAt != null) {
                events.publishDomainEvent(
                        DomainEvents.orderPaid(workflowType, order.orderId(), amount, currency, paidAt));
            }
        }
        List<String> supplierIds = fulfillment.planSupplierIds(input.orderId());
        return new OrderView(order.orderId(), order.platformOrderNo(), paid, amount, currency, paidAt,
                supplierIds);
    }

    @Override
    public List<OrderRma> syncRmas(OrderWorkflowInput input) {
        List<OrderRma> rmas = rmaSync.sync(input.orderId());
        for (OrderRma rma : rmas) {
            RmaOutcome outcome = FulfillmentDeriver.outcomeFor(rma.rmaStatus());
            if (outcome != null) {
                events.publishDomainEvent(DomainEvents.rmaClosed(workflowType, rma.orderId(),
                        rma.rmaId(), outcome, rmaClosedAt(rma)));
            }
        }
        return rmas;
    }

    /**
     * 已落库 RMA 的事实时间（幂等锚派生输入）。缺失即装配 / 状态错误——<b>显式失败</b>，绝不静默传
     * {@code null}（{@code occurred_at} 是 Envelope 必填，null 会让消息过不了 {@code message.schema.json}）。
     */
    private static String rmaClosedAt(OrderRma rma) {
        if (rma.timestamps() == null || rma.timestamps().updatedAt() == null) {
            throw new IllegalStateException("rma.closed 缺事实时间（OrderRma.timestamps.updatedAt）: "
                    + rma.rmaId() + "——不静默产出非法 envelope");
        }
        return rma.timestamps().updatedAt();
    }

    @Override
    public OrderSummary summarize(OrderWorkflowInput input) {
        OrderAggregate aggregate = load(input.orderId());
        return new OrderSummary(aggregate.order().fulfillmentStatus(),
                aggregate.purchaseOrders().stream().map(PurchaseOrder::purchaseOrderId).toList());
    }

    private OrderAggregate load(String orderId) {
        return OrderAggregate.of(store.getOrderById(orderId).orElseThrow(() -> new IllegalStateException(
                "OrderStore 无此订单聚合: " + orderId + "（订单须先经同步落库）")));
    }
}
