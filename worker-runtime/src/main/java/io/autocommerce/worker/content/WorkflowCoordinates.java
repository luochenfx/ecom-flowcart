package io.autocommerce.worker.content;

import io.temporal.activity.Activity;

/**
 * Temporal workflow 坐标提供器（#20 AC-6）：在 {@code ContentChainActivitiesImpl.runStep} 抛
 * {@link io.autocommerce.content.ContentChainFailedException} 时，把 Temporal workflowId /
 * runId 写进 {@code sys.workflow.failed} 事件 payload。
 *
 * <p>用接口而非在 activity 实现里直取 {@link Activity}，是为了**测试可注入**：不需要 activity 上下文
 * 的单测（{@code ContentChainActivitiesImplTest} 直接调 activity 实现）注入静态桩；生产装配与 E2E
 * 统一走 {@link #fromActivityExecutionContext()}——E2E 的 activity 同样跑在真实 activity 上下文里
 * （TestWorkflowEnvironment 自带 worker），所以拿得到真实 runId。
 *
 * <p><b>2026-09-11 修正（#41 review）</b>：此前 javadoc 声称"生产 worker 装配时实现走
 * ActivityExecutionContext 取真实坐标"，但 {@code ContentWorkerFactory.start} 实际传的是字面量
 * {@code unbound-workflow-id} / {@code unbound-run-id}，**全仓没有任何代码取过真实坐标**；E2E 也
 * 以"失败瞬间 runId 未定"为由填了 workflowId 占位——该理由不成立：activity 上下文里 workflowId /
 * runId 都是现成的。现已真接上。
 */
public interface WorkflowCoordinates {

    String workflowId();

    String runId();

    /**
     * 生产口径：从当前 activity 执行上下文取真实坐标。
     *
     * <p><b>惰性求值</b>——每次调用现问一次 {@link Activity#getExecutionContext()}，所以构造期传入是
     * 安全的，真正取值发生在硬失败要发事件那一刻。在 activity 上下文之外调用会抛 Temporal 的运行时
     * 异常，这正是想要的失败姿势：宁可炸，也不要静默填一个看起来像真的占位值。
     */
    static WorkflowCoordinates fromActivityExecutionContext() {
        return new WorkflowCoordinates() {
            @Override
            public String workflowId() {
                return Activity.getExecutionContext().getInfo().getWorkflowId();
            }

            @Override
            public String runId() {
                return Activity.getExecutionContext().getInfo().getRunId();
            }
        };
    }
}
