package io.autocommerce.worker.order;

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

import java.util.Objects;

/**
 * 采购单链 workflow 启动器（client 侧入口）：确定性 workflowId + execution 级幂等（specs/0003 §6 第一级）。
 *
 * <p>订单链把采购单作为 <b>child workflow</b> 起（{@link OrderWorkflowImpl}），本启动器供"独立驱动单张
 * 采购单"（运维重放 / 单测 / demo）使用——两者共用同一个 workflowId 口径
 * （{@link PurchaseRuntime#workflowIdFor(String, String)}），故谁先起谁生效、重复 start 复用既有 execution。
 *
 * <p>策略组合与 {@link OrderWorkflowLauncher} 同口径：前一次 failed → 放行新 run；前一次 completed →
 * 拒绝新 run（采购已履约，误触不得重复下单 / 重复回传）；运行中的重复触发被拒（不并发双跑同一采购单）。
 */
public final class PurchaseWorkflowLauncher {

    private final WorkflowClient client;
    private final String taskQueue;

    public PurchaseWorkflowLauncher(WorkflowClient client, String taskQueue) {
        this.client = Objects.requireNonNull(client, "WorkflowClient 必填");
        if (taskQueue == null || taskQueue.isBlank()) {
            throw new IllegalArgumentException("taskQueue 必填");
        }
        this.taskQueue = taskQueue;
    }

    /** 默认队列（{@link OrderRuntime#TASK_QUEUE}）的启动器。 */
    public static PurchaseWorkflowLauncher standard(WorkflowClient client) {
        return new PurchaseWorkflowLauncher(client, OrderRuntime.TASK_QUEUE);
    }

    /** 同步跑完取结果（demo / 测试 / 需要立即知道采购单收敛与否的调用方）。 */
    public PurchaseWorkflowResult run(PurchaseWorkflowInput input) {
        Objects.requireNonNull(input, "input 必填");
        return client.newWorkflowStub(PurchaseWorkflow.class, optionsFor(input)).run(input);
    }

    /** 异步启动（不阻塞）：返回 typed stub，结果经 {@link PurchaseWorkflow#run} 阻塞取回。 */
    public PurchaseWorkflow start(PurchaseWorkflowInput input) {
        Objects.requireNonNull(input, "input 必填");
        PurchaseWorkflow workflow = client.newWorkflowStub(PurchaseWorkflow.class, optionsFor(input));
        WorkflowClient.start(workflow::run, input);
        return workflow;
    }

    /** 由采购坐标反推运行中的 workflow stub（查状态 / 发 signal 用）。 */
    public PurchaseWorkflow stub(PurchaseWorkflowInput input) {
        return client.newWorkflowStub(PurchaseWorkflow.class, optionsFor(input));
    }

    private WorkflowOptions optionsFor(PurchaseWorkflowInput input) {
        return WorkflowOptions.newBuilder()
                .setWorkflowId(PurchaseRuntime.workflowIdFor(input.orderId(), input.supplierId()))
                .setTaskQueue(taskQueue)
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .build();
    }
}
