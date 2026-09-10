package io.autocommerce.content;

import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.content.model.ContentWorkingSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 内容链编排（进程内）：按 {@link ContentPlan} 顺序执行首批 Step，产物以单稿制物化回文档。
 *
 * <p>职责边界（ADR-0008）：本类只做"顺序 + 收口"，不做状态机、不持久化、不感知平台/Temporal——
 * 确定性编排壳（Temporal workflow，每步一个 activity）落在 composition root（worker-runtime），
 * 与本类共享 {@link ContentStepExecutor} 的单步语义。
 *
 * <p>与铺货链分离（specs/0006 §2）：重铺 ≠ 重生成；本链产物是"Listing 内容就绪"，
 * 铺货链（#11/#21）只读就绪 Listing。
 */
public final class ContentChainService {

    private final ContentStepExecutor executor;

    public ContentChainService(ContentStepExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor 必填");
    }

    /** 打开工作集、跑完计划、物化（便捷入口；文档落库由调用方负责）。 */
    public ContentChainOutcome run(ProductCatalog document, String listingId, ContentPlan plan) {
        return run(ContentWorkingSet.of(document, listingId), plan);
    }

    /** 在既有工作集上跑完计划（Temporal activity 跨步续跑时用）。 */
    public ContentChainOutcome run(ContentWorkingSet working, ContentPlan plan) {
        Objects.requireNonNull(plan, "plan 必填");
        List<ContentStepRun> runs = new ArrayList<>();
        for (ContentPlan.PlanStep step : plan.steps()) {
            runs.add(executor.execute(working, step));
        }
        ProductCatalog materialized = executor.materialize(working);
        ContentChainResult result = new ContentChainResult(
                working.listingId(), true, runs, working.degradedSteps());
        return new ContentChainOutcome(result, materialized);
    }

    /** 计划未注册 Step 的体检（装配期/测试用）：返回缺失的 Step id。 */
    public List<String> missingSteps(ContentPlan plan) {
        return plan.stepIds().stream().filter(id -> !executor.knows(id)).toList();
    }
}
