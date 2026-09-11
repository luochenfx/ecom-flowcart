package io.autocommerce.worker.content;

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

import java.util.Objects;

/**
 * 内容链 workflow 启动器（client 侧入口）：确定性 workflowId + execution 级幂等。
 *
 * <p>幂等口径（ADR-0003 / specs-0001 #11，内容链沿用同哲学）：登记动作 = workflow start 本身，
 * 不建幂等登记表。命中 {@code AlreadyStarted} 即视为重复提交并**返回既有 execution 的结果**
 * （ADR-0003 原文），调用方不需要自己去重的登记表。
 *
 * <p>策略组合（两条各管一半，缺一不可）：
 * <ul>
 *   <li>{@code WorkflowIdReusePolicy = ALLOW_DUPLICATE_FAILED_ONLY}（specs/0006 §9 明定）：
 *       前一次 **failed** → 放行新 run（硬依赖失败后重放/重跑）；前一次 **completed** → 拒绝新 run
 *       （内容已就绪，误触不得重复烧 LLM 的钱）；</li>
 *   <li>{@code WorkflowIdConflictPolicy = FAIL}：**运行中**的重复触发一样被拒（不并发双跑同一条链）。
 *       与默认值等价，此处显式写出以免"靠默认值"变成隐式契约。</li>
 * </ul>
 *
 * <p><b>已知缺口（需决策，勿当既成事实）</b>：一个**跑完但降级**的 Listing（degraded_steps 非空、
 * workflow completed）无法用同一 workflowId 重新生成内容——它命中"completed → 拒绝"。但
 * specs/0006 §2 把"人工重新生成内容"列为内容链的合法触发源。二者冲突，取舍见 spec 遗留 fog。
 */
public final class ContentWorkflowLauncher {

    private final WorkflowClient client;
    private final String taskQueue;

    public ContentWorkflowLauncher(WorkflowClient client, String taskQueue) {
        this.client = Objects.requireNonNull(client, "WorkflowClient 必填");
        if (taskQueue == null || taskQueue.isBlank()) {
            throw new IllegalArgumentException("taskQueue 必填");
        }
        this.taskQueue = taskQueue;
    }

    /** 默认队列（{@link ContentRuntime#TASK_QUEUE}）的启动器。 */
    public static ContentWorkflowLauncher standard(WorkflowClient client) {
        return new ContentWorkflowLauncher(client, ContentRuntime.TASK_QUEUE);
    }

    /** 异步启动（不阻塞）：返回 typed stub，结果经 {@link ContentWorkflow#run} 阻塞取回。 */
    public ContentWorkflow start(ContentWorkflowInput input) {
        Objects.requireNonNull(input, "input 必填");
        ContentWorkflow workflow = client.newWorkflowStub(ContentWorkflow.class, optionsFor(input.listingId()));
        WorkflowClient.start(workflow::run, input);
        return workflow;
    }

    /** 同步跑完取结果（demo / 测试 / 需要立即知道内容就绪与否的调用方）。 */
    public ContentWorkflowResult run(ContentWorkflowInput input) {
        Objects.requireNonNull(input, "input 必填");
        return client.newWorkflowStub(ContentWorkflow.class, optionsFor(input.listingId())).run(input);
    }

    /** 由 listingId 反推运行中的 workflow stub（查状态 / 发 signal 用）。 */
    public ContentWorkflow stub(String listingId) {
        return client.newWorkflowStub(ContentWorkflow.class, optionsFor(listingId));
    }

    private WorkflowOptions optionsFor(String listingId) {
        return WorkflowOptions.newBuilder()
                .setWorkflowId(ContentRuntime.workflowIdFor(listingId))
                .setTaskQueue(taskQueue)
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .build();
    }
}
