package io.autocommerce.worker.order;

/**
 * 订单 workflow 输入。
 *
 * @param orderId          确定性订单 id（= {@link OrderRuntime#workflowIdFor(String, String)} 业务键）
 * @param platform         销售平台（taobao / pdd / aliexpress / fixture-sales …）
 * @param platformOrderNo  平台订单号（DB 层 {@code (channel_id, platform_order_no)} unique 的业务键之一）
 */
public record OrderWorkflowInput(String orderId, String platform, String platformOrderNo) {
}
