package io.autocommerce.worker.flow;

import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.worker.content.ContentRuntime;
import io.autocommerce.worker.content.ContentWorkflow;
import io.autocommerce.worker.content.ContentWorkflowInput;
import io.autocommerce.worker.publish.PublishRuntime;
import io.autocommerce.worker.publish.PublishWorkflow;
import io.autocommerce.worker.publish.PublishWorkflowInput;
import io.autocommerce.worker.publish.PublishWorkflowResult;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;

import java.util.List;

/**
 * 编排链 workflow 实现（确定性调度壳，specs/0007 §4.4）：自身不做任何 I/O / 时钟 / store 读取——所有
 * 副作用在 activity 侧（{@link ListingFlowActivitiesImpl}）或子链里，这是 Temporal 重放的前提。
 *
 * <p>控制流：
 * <ol>
 *   <li><b>装配 Listing</b>（activity）：{@code store.get → ListingDraftFactory.draft → store.put}；</li>
 *   <li><b>内容链</b>（child workflow，同步等待）：硬依赖失败 ⇒ 子链抛异常冒泡 ⇒ 父链 failed；</li>
 *   <li><b>内容就绪断言</b>（activity）：{@code degraded_steps} 非空 ⇒ 抛不可重试失败 ⇒ 父链 failed，
 *       <b>不启动铺货链</b>；</li>
 *   <li><b>铺货链</b>（child workflow，同步等待，无超时）：停在 {@code AMBIGUOUS} ⇒ 子链挂起 ⇒ 父链
 *       同步挂起，可能持续数天（specs/0007 §4.5；任何自动超时都可能触发重复铺货，违反 ADR-0003）。</li>
 * </ol>
 *
 * <p><b>为什么必须用 {@code Workflow.newChildWorkflowStub}</b>：这是 Temporal 官方指定的父子链串联方式。
 * 若改成在 activity 内调 {@code ContentWorkflowLauncher} / {@code PublishWorkflowLauncher}，会命中官方
 * 反模式：activity 阻塞、重试时重复启动子链、子链不随父链取消传播。三条约束（本类零 I/O、activity 不碰
 * workflow API、activity 不调 launcher）由 {@code ListingFlowRuntimeArchitectureTest} 机械拦截。
 *
 * <p><b>跨 task queue 的 child workflow</b>：内容链在 {@link ContentRuntime#TASK_QUEUE}、铺货链在
 * {@link PublishRuntime#TASK_QUEUE} 上执行，要求两个队列的 worker 均在线（Temporal 支持跨队列父子链）。
 * 子链 workflowId 取确定性坐标（与两条子链的独立入口同口径），使 Temporal UI 上的父子树一眼可读。
 */
public final class ListingFlowWorkflowImpl implements ListingFlowWorkflow {

    private final ListingFlowActivities activities =
            Workflow.newActivityStub(ListingFlowActivities.class, ListingFlowActivityOptions.defaults());

    @Override
    public ListingFlowWorkflowResult run(ListingFlowWorkflowInput input) {
        // 1. 装配 Listing（落库，使内容链能读回）
        Listing assembled = activities.assembleListing(new AssembleListingInput(
                input.spuId(), input.channelId(), input.targetCategory(), input.locales()));

        // 2. 内容链（child workflow，同步等待）；硬依赖失败 ⇒ 冒泡至父链 ⇒ 整链 failed
        ContentWorkflow content = Workflow.newChildWorkflowStub(ContentWorkflow.class,
                childOptions(ContentRuntime.workflowIdFor(assembled.listingId()), ContentRuntime.TASK_QUEUE));
        content.run(new ContentWorkflowInput(input.spuId(), assembled.listingId(), input.plan()));

        // 3. 内容就绪断言（activity）：degraded_steps 非空 ⇒ 不可重试失败 ⇒ 不启动铺货链
        Listing ready = activities.assertContentReady(input.spuId(), assembled.listingId());

        // 4. 铺货链（child workflow，同步等待，无超时）；AMBIGUOUS ⇒ 父链同步挂起等 signal 裁定
        PublishWorkflow publish = Workflow.newChildWorkflowStub(PublishWorkflow.class,
                childOptions(PublishRuntime.workflowIdFor(ready.spuId(), ready.channelId()),
                        PublishRuntime.TASK_QUEUE));
        PublishWorkflowResult published = publish.run(new PublishWorkflowInput(ready));

        return new ListingFlowWorkflowResult(input.spuId(), assembled.listingId(), true,
                published.platformItemId(), published.platformItemUrl(), true, List.of(), null);
    }

    /**
     * 子链 workflow 选项：确定性 id + 复用策略（与两条子链的独立 launcher 同口径）。
     *
     * <p>复用策略取 {@code ALLOW_DUPLICATE_FAILED_ONLY}（ADR-0003）：子链已 completed（如内容已就绪）
     * 时重复启动被拒——这与 spec 登记的"degraded 但 completed 的 Listing 无法用同一 id 重生成内容"缺口
     * 一致，是既有语义而非本链引入的新行为。另注：{@code WorkflowIdConflictPolicy} 只存在于顶层
     * {@code WorkflowOptions}（本 SDK 版本的 {@code ChildWorkflowOptions.Builder} 未暴露），子链的
     * 并发去重由确定性 id + 复用策略承担。
     */
    private static ChildWorkflowOptions childOptions(String workflowId, String taskQueue) {
        return ChildWorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(taskQueue)
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .build();
    }
}
