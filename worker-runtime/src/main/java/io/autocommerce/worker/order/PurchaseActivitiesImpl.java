package io.autocommerce.worker.order;

import io.autocommerce.core.order.model.PurchaseOrder;
import io.autocommerce.core.order.model.PurchaseStatus;
import io.autocommerce.order.purchase.PurchaseFulfillmentService;
import io.autocommerce.worker.event.DomainEvents;
import io.autocommerce.worker.event.EventPublisher;

import java.time.Clock;
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
 */
public final class PurchaseActivitiesImpl implements PurchaseActivities {

    private final PurchaseFulfillmentService fulfillment;
    private final EventPublisher events;
    private final String workflowType;
    private final Clock clock;

    public PurchaseActivitiesImpl(PurchaseFulfillmentService fulfillment, EventPublisher events,
                                  String workflowType, Clock clock) {
        this.fulfillment = Objects.requireNonNull(fulfillment, "PurchaseFulfillmentService 必填");
        this.events = Objects.requireNonNull(events, "EventPublisher 必填");
        this.workflowType = Objects.requireNonNull(workflowType, "workflowType 必填");
        this.clock = Objects.requireNonNull(clock, "Clock 必填");
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
                    shipped.orderId(), shipped.purchaseOrderId(), clock.instant().toString()));
        }
        return shipped;
    }
}
