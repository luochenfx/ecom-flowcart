package io.autocommerce.worker.content;

import java.util.Objects;

/**
 * 单步 activity 输入：定位 + 计划位。
 *
 * @param listing 目标文档坐标（spuId + listingId）
 * @param stepId  计划中的 Step id（须与 SPI 注册的 Step 声明一致）
 * @param critical 硬依赖位：true = 失败即内容链 failed；false = 降级留痕、链路继续
 *                 （语义来源 = {@link io.autocommerce.content.ContentPlan.PlanStep}，此处随 workflow 载荷过界）
 */
public record ContentStepInput(ListingRef listing, String stepId, boolean critical) {

    public ContentStepInput {
        Objects.requireNonNull(listing, "listing 必填");
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId 必填");
        }
    }
}
