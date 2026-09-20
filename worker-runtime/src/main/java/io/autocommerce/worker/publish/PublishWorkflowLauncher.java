package io.autocommerce.worker.publish;

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

import java.util.Objects;

/**
 * 铺货链 workflow 启动器（client 侧入口）：确定性 workflowId + execution 级幂等（ADR-0003 / specs/0001 §2.2）。
 *
 * <p>幂等口径：登记动作 = workflow start 本身，不建幂等登记表。命中 {@code AlreadyStarted} 即视为重复
 * 提交并返回既有 execution（重复铺货指令 / 重试 / 重放共用同一入口）。
 *
 * <p>策略组合（ADR-0003 / specs/0001 §4，两条各管一半）：
 * <ul>
 *   <li>{@code WorkflowIdReusePolicy = ALLOW_DUPLICATE_FAILED_ONLY}：前一次 <b>failed</b>（REJECTED /
 *       FAILED）→ 放行新 run（人工修复后重铺 / 重放）；前一次 <b>completed</b>（PUBLISHED）或
 *       <b>running</b>（AMBIGUOUS 挂起）→ 拒绝新 run（不重复铺货）；</li>
 *   <li>{@code WorkflowIdConflictPolicy = FAIL}：<b>运行中</b>的重复触发被拒（不并发双跑同一 Listing）
 *       ——与默认值等价，显式写出以免"靠默认值"变成隐式契约。</li>
 * </ul>
 */
public final class PublishWorkflowLauncher {

    private final WorkflowClient client;
    private final String taskQueue;

    public PublishWorkflowLauncher(WorkflowClient client, String taskQueue) {
        this.client = Objects.requireNonNull(client, "WorkflowClient 必填");
        if (taskQueue == null || taskQueue.isBlank()) {
            throw new IllegalArgumentException("taskQueue 必填");
        }
        this.taskQueue = taskQueue;
    }

    /** 默认队列（{@link PublishRuntime#TASK_QUEUE}）的启动器。 */
    public static PublishWorkflowLauncher standard(WorkflowClient client) {
        return new PublishWorkflowLauncher(client, PublishRuntime.TASK_QUEUE);
    }

    /** 同步跑完取结果（demo / 测试 / 需要立即知道铺货收敛与否的调用方）。 */
    public PublishWorkflowResult run(PublishWorkflowInput input) {
        Objects.requireNonNull(input, "input 必填");
        return client.newWorkflowStub(PublishWorkflow.class, optionsFor(input)).run(input);
    }

    /**
     * 异步启动（不阻塞）：返回 typed stub。AMBIGUOUS 挂起时用该 stub 发 signal（见
     * {@link PublishWorkflow} 的三条出边），再经 {@link PublishWorkflow#run} 阻塞取回结果。
     */
    public PublishWorkflow start(PublishWorkflowInput input) {
        Objects.requireNonNull(input, "input 必填");
        PublishWorkflow workflow = client.newWorkflowStub(PublishWorkflow.class, optionsFor(input));
        WorkflowClient.start(workflow::run, input);
        return workflow;
    }

    /** 由 Listing 坐标反推运行中的 workflow stub（查状态 / 发 signal 用）。 */
    public PublishWorkflow stub(PublishWorkflowInput input) {
        Objects.requireNonNull(input, "input 必填");
        return client.newWorkflowStub(PublishWorkflow.class, optionsFor(input));
    }

    private WorkflowOptions optionsFor(PublishWorkflowInput input) {
        return WorkflowOptions.newBuilder()
                .setWorkflowId(PublishRuntime.workflowIdFor(input.listing().spuId(), input.listing().channelId()))
                .setTaskQueue(taskQueue)
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .build();
    }
}
