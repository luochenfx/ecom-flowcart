package io.autocommerce.worker.flow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 编排链 workflow（specs/0007 §4）：每 {@code (spuId, channelId)} 一个
 * {@code fulfillment-{spuId}-{channelId}} 执行。
 *
 * <p>它是**唯一的新架构层**：把此前物理断开的「采集产物 → 内容就绪 → 铺货收敛」串起来。自身不含业务
 * 逻辑，只做调度与传参——业务仍在各模块内（content / publish），模块间不产生新的强耦合（禁环②）。
 *
 * <p>控制流（{@link ListingFlowWorkflowImpl}）：装配 Listing（activity）→ 内容链（child workflow）→
 * 内容就绪断言（activity）→ 铺货链（child workflow）→ 收敛。
 *
 * <p>确定性 workflowId = {@link ListingFlowRuntime#workflowIdFor}：与内容链
 * {@code ContentRuntime#workflowIdFor}、铺货链 {@code PublishRuntime#workflowIdFor} 同源于同一 Listing。
 */
@WorkflowInterface
public interface ListingFlowWorkflow {

    /**
     * 跑完整条编排链并收敛为铺货结果。内容硬依赖失败 / 内容未就绪 ⇒ 本方法失败（不返回结果）；
     * 铺货 {@code AMBIGUOUS} ⇒ 本方法同步挂起（非终态，等 signal 裁定）。
     */
    @WorkflowMethod
    ListingFlowWorkflowResult run(ListingFlowWorkflowInput input);
}
