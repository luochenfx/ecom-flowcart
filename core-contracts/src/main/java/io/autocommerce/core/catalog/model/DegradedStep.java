package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Step 级降级记录（schema: DegradedStep，Listing.degraded_steps 元素；specs/0006 §5/§6）。
 *
 * <p>非硬依赖 Step 重试耗尽 → 该 Step 产物取缺省值（原文 / 默认加价率）并在此登记一行，
 * 内容链继续（"不阻铺货"）；硬依赖 Step 失败不降级——内容链 failed（specs/0006 §5）。
 * 记录本身即 HITL 信号：看板按 degraded_steps 非空筛出需人工复核的 Listing。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DegradedStep(String step, String reason) {
}
