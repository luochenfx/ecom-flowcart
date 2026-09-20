package io.autocommerce.worker.order;

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

import java.util.Objects;

/**
 * 订单链 workflow 启动器（client 侧入口）：确定性 workflowId + execution 级幂等（specs/0003 §6 第一级）。
 *
 * <p>幂等口径（ADR-0003 / specs/0003 §6）：登记动作 = workflow start 本身，不建幂等登记表。命中
 * {@code AlreadyStarted} 即视为重复提交并返回既有 execution 结果（吸收 webhook 重推 / 游标重读 /
 * 手工重放）。
 *
 * <p>策略组合（两条各管一半）：
 * <ul>
 *   <li>{@code WorkflowIdReusePolicy = ALLOW_DUPLICATE_FAILED_ONLY}：前一次 failed → 放行新 run
 *       （失败后重放）；前一次 completed → 拒绝新 run（订单已履约，误触不得重复下单 / 重复回传）；</li>
 *   <li>{@code WorkflowIdConflictPolicy = FAIL}：运行中的重复触发被拒（不并发双跑同一订单）。</li>
 * </ul>
 */
public final class OrderWorkflowLauncher {

    private final WorkflowClient client;
    private final String taskQueue;

    public OrderWorkflowLauncher(WorkflowClient client, String taskQueue) {
        this.client = Objects.requireNonNull(client, "WorkflowClient 必填");
        if (taskQueue == null || taskQueue.isBlank()) {
            throw new IllegalArgumentException("taskQueue 必填");
        }
        this.taskQueue = taskQueue;
    }

    /** 默认队列（{@link OrderRuntime#TASK_QUEUE}）的启动器。 */
    public static OrderWorkflowLauncher standard(WorkflowClient client) {
        return new OrderWorkflowLauncher(client, OrderRuntime.TASK_QUEUE);
    }

    /** 同步跑完取结果（demo / 测试 / 需要立即知道订单收敛与否的调用方）。 */
    public OrderWorkflowResult run(OrderWorkflowInput input) {
        Objects.requireNonNull(input, "input 必填");
        return client.newWorkflowStub(OrderWorkflow.class, optionsFor(input)).run(input);
    }

    /** 异步启动（不阻塞）：返回 typed stub，结果经 {@link OrderWorkflow#run} 阻塞取回。 */
    public OrderWorkflow start(OrderWorkflowInput input) {
        Objects.requireNonNull(input, "input 必填");
        OrderWorkflow workflow = client.newWorkflowStub(OrderWorkflow.class, optionsFor(input));
        WorkflowClient.start(workflow::run, input);
        return workflow;
    }

    /** 由订单坐标反推运行中的 workflow stub（查状态 / 发 signal 用）。 */
    public OrderWorkflow stub(OrderWorkflowInput input) {
        return client.newWorkflowStub(OrderWorkflow.class, optionsFor(input));
    }

    private WorkflowOptions optionsFor(OrderWorkflowInput input) {
        return WorkflowOptions.newBuilder()
                .setWorkflowId(OrderRuntime.workflowIdFor(input.platform(), input.platformOrderNo()))
                .setTaskQueue(taskQueue)
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .build();
    }
}
