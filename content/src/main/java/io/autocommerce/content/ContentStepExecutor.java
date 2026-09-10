package io.autocommerce.content;

import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.step.AiStep;
import io.autocommerce.core.step.StepDescriptor;
import io.autocommerce.core.step.StepExecutionException;
import io.autocommerce.core.step.StepOutcome;
import io.autocommerce.core.step.StepResult;
import io.autocommerce.content.model.ContentWorkingSet;
import io.autocommerce.content.step.ListingStepContext;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单 Step 执行器（#20）：把"执行一个 Step + 降级/硬失败判定 + 物化"收在一处，供两条编排路径复用——
 * <ul>
 *   <li>进程内顺序编排 {@link ContentChainService}（测试 / demo / 非 Temporal 调用方）；</li>
 *   <li>Temporal 编排（worker-runtime）：workflow 每步一个 activity，activity 内只执行一步。</li>
 * </ul>
 * 单点实现保证"降级 vs 硬失败"语义在任何编排形态下一致（specs/0006 §5）。
 *
 * <p>失败语义（specs/0006 §5/§8）：
 * <ul>
 *   <li>Step 返回 {@code DEGRADED} → 产物已按缺省写入（或字段保持上游值 = 原文），登记
 *       {@code degraded_steps}，内容链继续；</li>
 *   <li>Step 抛 {@link StepExecutionException} 且 plan 标 {@code critical} → 抛
 *       {@link ContentChainFailedException}（内容链 failed，Listing 不进铺货队列）；</li>
 *   <li>Step 抛异常且非 critical → 按降级处理（字段保持上游值 = specs §5"产物缺省（原文）"），
 *       内容链继续。</li>
 * </ul>
 *
 * <p>时钟注入（{@link Clock}）：{@code provenance.updated_at} 不由 {@code Instant.now()} 直取，
 * 便于测试固定时间；生产传入 UTC 时钟。
 */
public final class ContentStepExecutor {

    private final Map<String, AiStep> stepsById;
    private final Clock clock;

    /** @param steps SPI 装配得到的 Step 清单（无中央注册表；id 重复即装配错误） */
    public ContentStepExecutor(List<AiStep> steps, Clock clock) {
        Objects.requireNonNull(steps, "steps 必填");
        this.clock = Objects.requireNonNull(clock, "clock 必填");
        Map<String, AiStep> byId = new LinkedHashMap<>();
        for (AiStep step : steps) {
            StepDescriptor descriptor = step.descriptor();
            if (byId.put(descriptor.id(), step) != null) {
                throw new IllegalArgumentException("Step id 重复注册: " + descriptor.id());
            }
        }
        this.stepsById = Map.copyOf(byId);
    }

    /** 已注册 Step 的声明（装配校验 / 契约测试用）。 */
    public List<StepDescriptor> descriptors() {
        return stepsById.values().stream().map(AiStep::descriptor).toList();
    }

    public boolean knows(String stepId) {
        return stepsById.containsKey(stepId);
    }

    /**
     * 执行计划中的一步。
     *
     * @throws IllegalStateException       计划引用了未注册的 Step（装配错误，不静默跳过）
     * @throws ContentChainFailedException 硬依赖 Step 失败
     */
    public ContentStepRun execute(ContentWorkingSet working, ContentPlan.PlanStep planStep) {
        AiStep step = stepsById.get(planStep.stepId());
        if (step == null) {
            throw new IllegalStateException("计划引用了未注册的 Step: " + planStep.stepId()
                    + "（SPI 是否已装载该实现？）");
        }
        ListingStepContext context = new ListingStepContext(working, step.descriptor().params());
        try {
            StepResult result = step.execute(context);
            switch (result.outcome()) {
                case DEGRADED -> {
                    // Step 自报降级（未抛异常，产物已按缺省写入/字段保持上游值）：登记留痕。
                    // 留痕是链路的单一出口，不依赖每个 Step 自觉——否则 degraded_steps 会漏记。
                    String reason = result.reason() == null || result.reason().isBlank()
                            ? "Step 降级（产物取缺省：原文/成本价）" : result.reason();
                    working.addDegradedStep(planStep.stepId(), reason);
                    return new ContentStepRun(planStep.stepId(), result.outcome(), reason, context.modelCalls());
                }
                case OK -> {
                    // 重跑恢复后必须清掉历史留痕，否则看板对着一份已补齐的 Listing 一直亮 HITL。
                    working.clearDegradedStep(planStep.stepId());
                    return new ContentStepRun(planStep.stepId(), result.outcome(), result.reason(),
                            context.modelCalls());
                }
                default -> {
                    // SKIPPED = 本步未给出结论（既非产物正常也非降级）→ 不动留痕，保留上次判定。
                    return new ContentStepRun(planStep.stepId(), result.outcome(), result.reason(),
                            context.modelCalls());
                }
            }
        } catch (StepExecutionException e) {
            if (planStep.critical()) {
                throw new ContentChainFailedException(planStep.stepId(), e.getMessage(), e);
            }
            String reason = "非硬依赖 Step 失败，产物保持上游值（原文/成本价）：" + e.getMessage();
            working.addDegradedStep(planStep.stepId(), reason);
            return new ContentStepRun(planStep.stepId(), StepOutcome.DEGRADED, reason, context.modelCalls());
        }
    }

    /** 物化工作集为落库文档（时间戳取自注入时钟）。 */
    public ProductCatalog materialize(ContentWorkingSet working) {
        return working.toDocument(Instant.now(clock).toString());
    }
}
