package io.autocommerce.core.order.model;

/**
 * 售后单入口（schema: OrderRma.type）。REFUND = 国内退款入口；DISPUTE = 跨境纠纷入口（可冻资）。
 */
public enum RmaType {
    REFUND,
    DISPUTE
}
