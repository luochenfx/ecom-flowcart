package io.autocommerce.api.support;

import io.autocommerce.worker.flow.ListingFlowWorkflowResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 查询端点测试用探针 workflow：以可控方式进入 {@code RUNNING} / {@code COMPLETED} / {@code FAILED}
 * 三种 Temporal execution 状态，用于验证 {@code GET /api/v1/flows/{workflowId}} 的状态映射
 * （specs/0007 §4.5：running 是合法响应，与 failed 可区分）。
 *
 * <p>返回类型与编排链一致（{@link ListingFlowWorkflowResult}），使查询端点的结果回读路径（typed
 * {@code getResult}）被真实覆盖。这是测试专用探针（test scope），不替代真实编排链。
 */
@WorkflowInterface
public interface ProbeFlow {

    @WorkflowMethod
    ListingFlowWorkflowResult run(String mode);
}
