package io.autocommerce.core.order.model;

/**
 * 内部 canonical 订单履约轴（schema: FulfillmentStatus）。
 * v1 草案，随 #9/#10 消费时细化；由 workflow 派生，不做逐平台全量映射。
 */
public enum FulfillmentStatus {
    PENDING_PAYMENT,
    AWAITING_PURCHASE,
    PURCHASING,
    PARTIALLY_SHIPPED,
    SHIPPED,
    COMPLETED,
    CANCELLED,
    REFUNDING,
    DISPUTED,
    UNKNOWN
}
