package io.autocommerce.worker.order;

import io.autocommerce.core.order.model.FulfillmentStatus;

import java.util.List;

/**
 * 订单链摘要（{@link OrderActivities#summarize} 返回）：落库后的销售履约轴 + 采购单 id 集。
 *
 * @param fulfillmentStatus 销售履约轴（canonical，由采购集合 / RMA 派生后落库）
 * @param purchaseOrderIds  该订单当前全部采购单 id
 */
public record OrderSummary(FulfillmentStatus fulfillmentStatus, List<String> purchaseOrderIds) {
}
