package io.autocommerce.worker.order;

/**
 * 采购单 workflow 输入：一张销售订单拆出的<b>其中一个供应商</b>的采购坐标。
 *
 * <p>跨供应商拆单（Order → 1:N）后，每个供应商一张采购单 = 一个 {@link PurchaseWorkflow} 执行
 * （workflowId = {@code purchase-{orderId}-{supplierId}}，与 {@code PurchaseOrder.purchase_order_id} 同源）。
 *
 * @param orderId    所属销售订单 id
 * @param supplierId 目标供应商 id（拆单结果之一）
 */
public record PurchaseWorkflowInput(String orderId, String supplierId) {
}
