package io.autocommerce.worker.content;

import io.autocommerce.content.ContentStepRun;
import io.autocommerce.core.catalog.model.DegradedStep;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/**
 * 内容链 activity 契约（每步一个 activity，ADR-0002："有状态链进 workflow、无状态分发进总线"）。
 *
 * <p>为什么 activity 只做"一步 + 落库"：Step 本身是无状态插件（specs/0006 §1），一步的读改写可以在
 * 一次调用内闭合；把整条链塞进一个 activity 会丢掉"逐步可观测 / 逐步重放"的编排价值，也违背
 * {@link io.autocommerce.content.ContentStepExecutor} 的单步语义拆分。
 *
 * <p>失败语义（specs/0006 §5）由 ContentStepExecutor 单点决定：非硬依赖失败 → 本方法正常返回
 * {@code DEGRADED} 记录；硬依赖失败 → 抛异常（Temporal 按 RetryOptions 判定，耗尽即 workflow failed）。
 */
@ActivityInterface
public interface ContentChainActivities {

    /** 执行计划中的一步，并把物化后的文档写回 CatalogStore（幂等：重跑覆盖同字段）。 */
    @ActivityMethod
    ContentStepRun runStep(ContentStepInput input);

    /** 回读 Listing 当前降级留痕（内容就绪的组成部分，由已落库文档读取）。 */
    @ActivityMethod
    List<DegradedStep> degradedSteps(ListingRef listing);
}

