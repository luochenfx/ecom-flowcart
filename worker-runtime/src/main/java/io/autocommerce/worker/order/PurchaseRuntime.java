package io.autocommerce.worker.order;

/**
 * 采购单链运行时常量（单一事实源）。
 *
 * <p>采购 workflow 与订单链共用 task queue（{@link OrderRuntime#TASK_QUEUE}），但 workflowId 独立：
 * {@code purchase-{orderId}-{supplierId}}（= {@code PurchaseOrder.purchase_order_id}，对
 * specs/0003 §2「PurchaseOrder 自有生命周期 / workflow」的确定性业务键）。
 */
public final class PurchaseRuntime {

    /** workflowId 前缀（对齐 purchase_order_id 口径）。 */
    public static final String WORKFLOW_ID_PREFIX = "purchase-";

    private PurchaseRuntime() {
    }

    /** {@code purchase-{orderId}-{supplierId}}：确定性 workflowId，与 PurchaseOrder id 同源。 */
    public static String workflowIdFor(String orderId, String supplierId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId 必填");
        }
        if (supplierId == null || supplierId.isBlank()) {
            throw new IllegalArgumentException("supplierId 必填");
        }
        return WORKFLOW_ID_PREFIX + orderId + "-" + supplierId;
    }
}
