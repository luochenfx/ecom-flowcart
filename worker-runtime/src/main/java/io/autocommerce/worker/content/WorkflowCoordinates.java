package io.autocommerce.worker.content;

import io.temporal.activity.ActivityExecutionContext;

/**
 * Temporal workflow 坐标提供器（#20 AC-6）：在 {@code ContentChainActivitiesImpl.runStep} 抛
 * {@link io.autocommerce.content.ContentChainFailedException} 时，把 Temporal workflowId /
 * runId 写进 {@code sys.workflow.failed} 事件 payload。
 *
 * <p>用接口而非直接注入 {@link ActivityExecutionContext} 是为了**测试可注入**：E2E 测试用
 * 静态值（TestWorkflowEnvironment 没有真正的 ActivityExecutionContext 可拿），生产 worker 装配
 * 时实现走 {@link ActivityExecutionContext} 取真实坐标。
 */
public interface WorkflowCoordinates {

    String workflowId();

    String runId();
}