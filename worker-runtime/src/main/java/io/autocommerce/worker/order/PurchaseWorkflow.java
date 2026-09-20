package io.autocommerce.worker.order;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 采购单链 workflow（#22 AC-4：「采购单自有状态轴 / workflow」）：每张采购单一个
 * {@code purchase-{orderId}-{supplierId}} 执行。
 *
 * <p>与 {@link OrderWorkflow} 同级、同 worker、同 task queue，但生命周期独立：一张销售订单拆 N 张采购单
 * = N 个本 workflow 并行履约。销售侧 {@code SHIPPED/COMPLETED} 由这些采购 workflow 的结果集合
 * <b>派生</b>（{@code FulfillmentDeriver.deriveSales}），不双写。
 *
 * <p>确定性 workflowId = {@link PurchaseRuntime#workflowIdFor(String, String)}，与
 * {@code PurchaseOrder.purchase_order_id} 同源（specs/0003 §2/§6）：重复 start 复用既有 execution，
 * 采购下单 / 支付是花钱的外部副作用，绝不能重复执行。
 */
@WorkflowInterface
public interface PurchaseWorkflow {

    /** 跑完「下采购单（先解密地址）→ 发货回传」并收敛；硬依赖失败则本方法失败（不返回结果）。 */
    @WorkflowMethod
    PurchaseWorkflowResult run(PurchaseWorkflowInput input);
}
