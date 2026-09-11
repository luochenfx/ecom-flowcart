package io.autocommerce.worker.content;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 内容链 workflow（#20 AC-1）：每 Listing 一个 {@code content-{listingId}} 执行。
 *
 * <p>workflowId 确定性 = {@link ContentRuntime#workflowIdFor(String)}（对齐 #11 铺货
 * {@code listing-{productId}-{channelId}} 的 ADR-0003 幂等语义）：同一 Listing 重复触发 → 复用既有
 * 执行而非并发跑第二遍（LLM 调用是花钱的外部副作用，重复执行 = 重复烧钱）。
 */
@WorkflowInterface
public interface ContentWorkflow {

    /** 跑完计划并收敛为内容就绪态；硬依赖失败则本方法失败（不返回结果）。 */
    @WorkflowMethod
    ContentWorkflowResult run(ContentWorkflowInput input);
}
