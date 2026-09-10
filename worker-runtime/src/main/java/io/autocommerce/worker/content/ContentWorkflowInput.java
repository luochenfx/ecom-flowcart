package io.autocommerce.worker.content;

import io.autocommerce.content.ContentPlan;

import java.util.Objects;

/**
 * 内容链 workflow 输入（#20 AC-1）：对已建 Listing 跑一次内容链。
 *
 * <p>{@code plan} 随 workflow 载荷过界：Step 顺序 + 硬依赖位属**链路配置**（specs/0006 §2/§5），
 * 与"装哪些 Step"（SPI 装配）解耦——同一批 Step，国内链可 {@code without(i18n.backfill)}，
 * 跨境链保留；计划本身可被调用方定制而不改代码。
 */
public record ContentWorkflowInput(String spuId, String listingId, ContentPlan plan) {

    public ContentWorkflowInput {
        if (spuId == null || spuId.isBlank()) {
            throw new IllegalArgumentException("spuId 必填");
        }
        if (listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("listingId 必填");
        }
        Objects.requireNonNull(plan, "内容链计划必填");
    }
}
