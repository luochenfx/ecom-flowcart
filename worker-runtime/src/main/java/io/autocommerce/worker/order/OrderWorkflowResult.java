package io.autocommerce.worker.order;

import io.autocommerce.core.order.model.FulfillmentStatus;

import java.util.List;

/**
 * 订单链执行结果。
 *
 * @param orderId            订单 id
 * @param platformOrderNo    平台订单号
 * @param fulfillmentStatus  收敛后的销售履约轴
 * @param purchaseOrderIds   拆出的采购单 id（跨供应商拆单 → 可能多张）
 * @param rmaIds             该订单关联的售后单 id
 */
public record OrderWorkflowResult(String orderId, String platformOrderNo,
                                  FulfillmentStatus fulfillmentStatus,
                                  List<String> purchaseOrderIds, List<String> rmaIds) {
}
