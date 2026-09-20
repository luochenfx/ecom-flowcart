package io.autocommerce.worker.order;

import io.autocommerce.core.order.model.PurchaseStatus;

/**
 * 采购单 workflow 结果（单张采购单收敛快照）。
 *
 * <p>销售侧 SHIPPED/COMPLETED 是这些采购 workflow 结果的<b>派生聚合</b>——本结果不回写销售轴
 * （无双写，specs/0003 §4）。
 *
 * @param orderId         所属销售订单 id
 * @param supplierId      供应商 id
 * @param purchaseOrderId 采购单 id（= 本 workflow 的 workflowId）
 * @param purchaseStatus  收敛后的采购轴状态
 */
public record PurchaseWorkflowResult(String orderId, String supplierId, String purchaseOrderId,
                                     PurchaseStatus purchaseStatus) {
}
