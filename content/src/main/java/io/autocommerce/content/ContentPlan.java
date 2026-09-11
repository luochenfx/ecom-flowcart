package io.autocommerce.content;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 内容链执行计划（specs/0006 §2/§5）：Step 顺序 + 每个 Step 是否硬依赖 + 可关闭。
 *
 * <p>"首批 Step 清单"与"哪些是硬依赖"属**链路配置**而非 Step 自身声明：同一 Step 在国内链可跳过、
 * 在跨境链是硬依赖（specs/0006 §8 `i18n.backfill`：硬依赖（跨境）；国内链可跳过）。因此顺序与
 * 硬依赖位在此表达，Step 只声明 id/字段/model_requirement（字段级契约，specs/0006 §3）。
 *
 * <p>成本极简控制面（specs/0006 §7 / #20 AC）：{@link #without(String)} 关闭指定 Step、
 * provider 档位由 {@code ModelResolver} 的 model 覆盖切换——无预算系统。
 */
public record ContentPlan(List<PlanStep> steps) {

    /** Step id 常量（specs/0006 §8 首批清单，与 Step 声明一一对应）。 */
    public static final String I18N_BACKFILL = "i18n.backfill";
    public static final String TITLE_REWRITE = "title.rewrite";
    public static final String DESC_GENERATE = "desc.generate";
    public static final String PRICE_STRATEGY = "price.strategy";
    public static final String MEDIA_PROCESS = "media.process";

    public ContentPlan {
        Objects.requireNonNull(steps, "steps 必填");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("内容链计划不能为空");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (PlanStep step : steps) {
            Objects.requireNonNull(step, "PlanStep 必填");
            if (!seen.add(step.stepId())) {
                throw new IllegalArgumentException("内容链计划中 Step 重复: " + step.stepId());
            }
        }
    }

    /**
     * 标准计划 = specs/0006 §8 首批清单按序：翻译回填 → 标题改写 → 描述生成 → 价格策略 → 媒体处理。
     * 硬依赖 = {@code i18n.backfill}（跨境无内容不可铺）与 {@code media.process}（无图不能铺）。
     * 国内链（无需多语言）用 {@code standard().without(I18N_BACKFILL)} 关闭翻译。
     */
    public static ContentPlan standard() {
        return new ContentPlan(List.of(
                new PlanStep(I18N_BACKFILL, true),
                new PlanStep(TITLE_REWRITE, false),
                new PlanStep(DESC_GENERATE, false),
                new PlanStep(PRICE_STRATEGY, false),
                new PlanStep(MEDIA_PROCESS, true)));
    }

    /** 关闭指定 Step（不在计划内 = 不执行、不登记降级）。 */
    public ContentPlan without(String stepId) {
        List<PlanStep> kept = new ArrayList<>();
        for (PlanStep step : steps) {
            if (!step.stepId().equals(stepId)) {
                kept.add(step);
            }
        }
        return new ContentPlan(kept);
    }

    public List<String> stepIds() {
        return steps.stream().map(PlanStep::stepId).toList();
    }

    /**
     * 计划中的一个 Step 位。
     *
     * @param stepId   Step id（须与 SPI 注册的 Step 声明一致）
     * @param critical true = 硬依赖：Step 抛异常**或自报 DEGRADED**（产物不完整）均判内容链 failed
     *                 （走重放/告警）；false = 可降级：产物取缺省（原文/默认加价率）+
     *                 写 {@code degraded_steps}，内容链继续
     */
    public record PlanStep(String stepId, boolean critical) {

        public PlanStep {
            if (stepId == null || stepId.isBlank()) {
                throw new IllegalArgumentException("plan step id 必填");
            }
        }
    }
}
