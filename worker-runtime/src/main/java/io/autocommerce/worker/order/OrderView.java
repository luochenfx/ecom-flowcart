package io.autocommerce.worker.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单就绪视图（{@link OrderActivities#prepare} 返回）。
 *
 * @param orderId        订单 id
 * @param platformOrderNo 平台订单号
 * @param paid           销售订单是否已过支付点（order.paid 判定）
 * @param paymentAmount  买家实付（可空：平台未给粒度时由 platform_raw 兜底）
 * @param currency       币种（可空）
 * @param paidAt         支付发生时间（可空）
 * @param supplierIds    拆单结果：该订单需要的供应商（确定性顺序）——订单链据此为每个供应商起一个
 *                       采购单 workflow（{@link PurchaseWorkflow}），实现 Order → 1:N 跨供应商拆单
 */
public record OrderView(String orderId, String platformOrderNo, boolean paid,
                        BigDecimal paymentAmount, String currency, String paidAt,
                        List<String> supplierIds) {
}
