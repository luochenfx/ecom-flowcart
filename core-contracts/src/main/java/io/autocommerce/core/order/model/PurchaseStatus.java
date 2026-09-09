package io.autocommerce.core.order.model;

/**
 * 采购 canonical 状态轴（schema: PurchaseStatus），独立于销售履约轴。
 */
public enum PurchaseStatus {
    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    COMPLETED,
    CANCELLED,
    REFUNDING,
    UNKNOWN
}
