package io.autocommerce.worker.content;

import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.content.ContentPlan;
import io.autocommerce.content.ContentStepExecutor;
import io.autocommerce.content.ContentStepRun;
import io.autocommerce.content.model.ContentWorkingSet;
import io.autocommerce.core.catalog.model.DegradedStep;
import io.autocommerce.core.catalog.model.ProductCatalog;

import java.util.List;
import java.util.Objects;

/**
 * 内容链 activity 实现：**单步 + 落库**（每步一次调用）。
 *
 * <p>为什么"每步都 read → 改 → write"而不是把文档揣在 workflow 里传递：
 * <ul>
 *   <li>payload 小：workflow 载荷只走坐标与执行记录，product 文档不进 history（文档会随媒体增长）；</li>
 *   <li>可观测：每步结束产物即刻落库，人工在铺货前就能看到中间稿，无需等整条链收敛；</li>
 *   <li>可续跑：activity 天然可重试，重跑同一步 = 覆盖同字段（Step 幂等，specs/0006 §5）。</li>
 * </ul>
 *
 * <p>失败语义不在此处判断——{@link ContentStepExecutor} 是唯一判定点（硬依赖失败抛
 * {@code ContentChainFailedException}，非硬依赖失败收敛为 {@code DEGRADED} 记录）。
 */
public final class ContentChainActivitiesImpl implements ContentChainActivities {

    private final CatalogStore store;
    private final ContentStepExecutor executor;

    public ContentChainActivitiesImpl(CatalogStore store, ContentStepExecutor executor) {
        this.store = Objects.requireNonNull(store, "CatalogStore 必填");
        this.executor = Objects.requireNonNull(executor, "ContentStepExecutor 必填");
    }

    @Override
    public ContentStepRun runStep(ContentStepInput input) {
        ContentWorkingSet working = ContentWorkingSet.of(load(input.listing()), input.listing().listingId());
        ContentStepRun run = executor.execute(working,
                new ContentPlan.PlanStep(input.stepId(), input.critical()));
        store.put(executor.materialize(working));
        return run;
    }

    @Override
    public List<DegradedStep> degradedSteps(ListingRef listing) {
        return ContentWorkingSet.of(load(listing), listing.listingId()).degradedSteps();
    }

    private ProductCatalog load(ListingRef listing) {
        return store.get(listing.spuId()).orElseThrow(() -> new IllegalStateException(
                "CatalogStore 无此 SPU 文档: " + listing.spuId()
                        + "（内容链的前置 = #19 采集已落库，或 Listing 装配已写回）"));
    }
}
