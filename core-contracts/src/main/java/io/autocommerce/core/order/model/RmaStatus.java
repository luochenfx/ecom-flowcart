package io.autocommerce.core.order.model;

/**
 * canonical 售后状态轴（schema: RmaStatus）。
 */
public enum RmaStatus {
    OPEN,
    WAITING_SELLER,
    WAITING_BUYER,
    PARTIAL_REFUNDED,
    REFUNDED,
    CLOSED,
    REJECTED,
    ESCALATED,
    UNKNOWN
}
