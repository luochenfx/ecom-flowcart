package io.autocommerce.worker.order;

import io.autocommerce.core.order.model.PurchaseOrder;
import io.autocommerce.core.order.model.PurchaseStatus;
import io.autocommerce.order.purchase.PurchaseFulfillmentService;
import io.autocommerce.worker.event.DomainEvents;
import io.autocommerce.worker.event.EventPublisher;

import java.util.Objects;

/**
 * 采购单链 activity 实现：**落库 + 外部副作用**，{@code purchase.shipped} 一律在落库之后广播
 * （specs/0016 §0.3）。
 *
 * <p>业务逻辑不在此处——拆单 / 下单 / 解密地址 / 回传发货都在 {@link PurchaseFulfillmentService}
 * （order 模块）。本类只做三件事：调服务、落库后发事件、回结果。故它是可被测的薄壳，且业务模块保持零
 * Temporal 依赖（禁环②）。
 *
 * <p><b>事件 A-prime 降级</b>：落库后、发事件前 crash → 事件丢失；消费端以业务库回读为准、
 * envelope.id 作幂等锚（重复广播由消费端去重）。事务基建不在本 slice。
 *
 * <p><b>幂等锚纪律</b>：{@code purchase.shipped} 的 {@code occurred_at} 取<b>已落库采购单</b>的事实
 * 时间（{@code PurchaseOrder.timestamps.updatedAt}），<b>不</b>取广播时刻——activity 失败重跑时
 * {@code returnShipment} 幂等早退返回同一已落库事实，故两次广播产出同一 envelope.id（见
 * {@link DomainEvents}）。本类不依赖时钟。
 */
public final class PurchaseActivitiesImpl implements PurchaseActivities {

    private final PurchaseFulfillmentService fulfillment;
    private final EventPublisher events;
    private final String workflowType;

    public PurchaseActivitiesImpl(PurchaseFulfillmentService fulfillment, EventPublisher events,
                                  String workflowType) {
        this.fulfillment = Objects.requireNonNull(fulfillment, "PurchaseFulfillmentService 必填");
        this.events = Objects.requireNonNull(events, "EventPublisher 必填");
        this.workflowType = Objects.requireNonNull(workflowType, "workflowType 必填");
    }

    @Override
    public void ensureAddressDecrypted(PurchaseWorkflowInput input) {
        fulfillment.ensureAddressDecrypted(input.orderId());
    }

    @Override
    public PurchaseOrder placePurchase(PurchaseWorkflowInput input) {
        return fulfillment.placePurchase(input.orderId(), input.supplierId());
    }

    @Override
    public PurchaseOrder returnShipment(PurchaseWorkflowInput input) {
        PurchaseOrder shipped = fulfillment.returnShipment(input.orderId(), input.supplierId());
        if (shipped.purchaseStatus() == PurchaseStatus.SHIPPED) {
            events.publishDomainEvent(DomainEvents.purchaseShipped(workflowType,
                    shipped.orderId(), shipped.purchaseOrderId(), fulfilledAt(shipped)));
        }
        return shipped;
    }

    /**
     * 已落库采购单的事实时间（幂等锚派生输入）。{@code returnShipment} 恒写 {@code timestamps}，缺失即
     * 装配 / 状态错误——<b>显式失败</b>，绝不静默传 {@code null}（{@code occurred_at} 是 Envelope 必填、
     * {@code format: date-time}，null 会让消息过不了 {@code message.schema.json}）。
     */
    private static String fulfilledAt(PurchaseOrder shipped) {
        if (shipped.timestamps() == null || shipped.timestamps().updatedAt() == null) {
            throw new IllegalStateException("purchase.shipped 缺事实时间（PurchaseOrder.timestamps.updatedAt）: "
                    + shipped.purchaseOrderId() + "——不静默产出非法 envelope");
        }
        return shipped.timestamps().updatedAt();
    }
}
