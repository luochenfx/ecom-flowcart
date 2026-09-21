package io.autocommerce.worker.flow;

import io.autocommerce.content.ContentPlan;
import io.autocommerce.core.catalog.model.CategoryRef;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

import java.util.List;
import java.util.Objects;

/**
 * 编排链 workflow 启动器（client 侧入口）：确定性 workflowId + execution 级幂等 + 链路类型→计划映射。
 *
 * <p>幂等口径（ADR-0003，与 {@code ContentWorkflowLauncher} / {@code PublishWorkflowLauncher} 一致）：
 * 登记动作 = workflow start 本身，不建幂等登记表。策略组合：
 * <ul>
 *   <li>{@code WorkflowIdReusePolicy = ALLOW_DUPLICATE_FAILED_ONLY}：前一次 failed → 放行新 run；
 *       前一次 completed / 挂起中（running）→ 拒绝新 run；</li>
 *   <li>{@code WorkflowIdConflictPolicy = FAIL}：运行中 / 挂起中的重复触发被拒（不并发双跑同一条链）。</li>
 * </ul>
 *
 * <p><b>链路类型 → {@link ContentPlan} 的映射在此完成</b>（{@link ContentPlanMapping}，可经构造器注入
 * 自定义映射）。请求方声明 {@link ContentChainKind}（国内 / 跨境），**不按 {@code channelId} 推导**——
 * 后者会让编排层知道 channel 的业务语义（specs/0007 §4.3）。映射发生在客户端侧，使 workflow 载荷自洽、
 * 重放确定性不受配置变更影响（见 {@link ContentPlanMapping} 类 javadoc）。
 */
public final class ListingFlowWorkflowLauncher {

    private final WorkflowClient client;
    private final String taskQueue;
    private final ContentPlanMapping planMapping;

    /** 默认映射（{@link ContentPlanMapping#standard()}）的启动器。 */
    public ListingFlowWorkflowLauncher(WorkflowClient client, String taskQueue) {
        this(client, taskQueue, ContentPlanMapping.standard());
    }

    /**
     * @param client      Temporal client
     * @param taskQueue   编排链 task queue
     * @param planMapping 链路类型 → 内容计划映射（可配置）
     */
    public ListingFlowWorkflowLauncher(WorkflowClient client, String taskQueue, ContentPlanMapping planMapping) {
        this.client = Objects.requireNonNull(client, "WorkflowClient 必填");
        if (taskQueue == null || taskQueue.isBlank()) {
            throw new IllegalArgumentException("taskQueue 必填");
        }
        this.taskQueue = taskQueue;
        this.planMapping = Objects.requireNonNull(planMapping, "ContentPlanMapping 必填");
    }

    /** 默认队列（{@link ListingFlowRuntime#TASK_QUEUE}）的启动器。 */
    public static ListingFlowWorkflowLauncher standard(WorkflowClient client) {
        return new ListingFlowWorkflowLauncher(client, ListingFlowRuntime.TASK_QUEUE);
    }

    /** 异步启动（不阻塞）：返回 typed stub，结果经 {@link ListingFlowWorkflow#run} 阻塞取回。 */
    public ListingFlowWorkflow start(String spuId, String channelId, CategoryRef targetCategory,
                                     List<String> locales, ContentChainKind chain) {
        ListingFlowWorkflow workflow =
                client.newWorkflowStub(ListingFlowWorkflow.class, optionsFor(spuId, channelId));
        WorkflowClient.start(workflow::run,
                inputFor(spuId, channelId, targetCategory, locales, chain));
        return workflow;
    }

    /** 同步跑完取结果（demo / 测试 / 需要立即知道链路是否收敛的调用方）。 */
    public ListingFlowWorkflowResult run(String spuId, String channelId, CategoryRef targetCategory,
                                         List<String> locales, ContentChainKind chain) {
        return client.newWorkflowStub(ListingFlowWorkflow.class, optionsFor(spuId, channelId))
                .run(inputFor(spuId, channelId, targetCategory, locales, chain));
    }

    /** 由坐标反推运行中的 workflow stub（查状态用）。 */
    public ListingFlowWorkflow stub(String spuId, String channelId) {
        return client.newWorkflowStub(ListingFlowWorkflow.class, optionsFor(spuId, channelId));
    }

    /** 链路类型 → {@link ContentPlan}（映射的唯一入口，见类 javadoc）。 */
    private ListingFlowWorkflowInput inputFor(String spuId, String channelId, CategoryRef targetCategory,
                                              List<String> locales, ContentChainKind chain) {
        ContentPlan plan = planMapping.planFor(chain);
        return new ListingFlowWorkflowInput(spuId, channelId, targetCategory, locales, plan);
    }

    private WorkflowOptions optionsFor(String spuId, String channelId) {
        return WorkflowOptions.newBuilder()
                .setWorkflowId(ListingFlowRuntime.workflowIdFor(spuId, channelId))
                .setTaskQueue(taskQueue)
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .build();
    }
}
