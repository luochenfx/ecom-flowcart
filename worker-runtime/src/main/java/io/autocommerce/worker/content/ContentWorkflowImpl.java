package io.autocommerce.worker.content;

import io.autocommerce.content.ContentPlan;
import io.autocommerce.content.ContentStepRun;
import io.autocommerce.core.catalog.model.DegradedStep;
import io.temporal.workflow.Workflow;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容链 workflow 实现（确定性编排壳）：顺序调度"每步一个 activity"，自身不做任何 I/O / 时钟 /
 * 随机数——所有副作用在 activity 侧（{@link ContentChainActivitiesImpl}），这是 Temporal 重放的前提。
 *
 * <p>为什么顺序写在 workflow 而非 activity：Step 顺序 + 硬依赖位是**链路配置**（specs/0006 §2/§5），
 * 改动应落在计划与编排层，而非散进每个 Step。workflow 里读 {@code input.plan()} 逐位调度，
 * Temporal Event History 即成为"这条链跑过什么"的真相（ADR-0002）。
 *
 * <p>硬依赖失败不经本类处理：activity 抛出的异常由 Temporal 按 {@link ContentActivityOptions} 的
 * RetryOptions 判定，耗尽后本 workflow 直接 failed——不吞异常、不降级（降级只发生在非硬依赖位，
 * 且已在 activity 内收敛为 {@code DEGRADED} 记录）。
 */
public final class ContentWorkflowImpl implements ContentWorkflow {

    private final ContentChainActivities activities =
            Workflow.newActivityStub(ContentChainActivities.class, ContentActivityOptions.defaults());

    @Override
    public ContentWorkflowResult run(ContentWorkflowInput input) {
        ListingRef listing = new ListingRef(input.spuId(), input.listingId());
        List<ContentStepRun> runs = new ArrayList<>();
        for (ContentPlan.PlanStep step : input.plan().steps()) {
            runs.add(activities.runStep(
                    new ContentStepInput(listing, step.stepId(), step.critical())));
        }
        List<DegradedStep> degradedSteps = activities.degradedSteps(listing);
        return new ContentWorkflowResult(input.spuId(), input.listingId(), runs, degradedSteps);
    }
}
