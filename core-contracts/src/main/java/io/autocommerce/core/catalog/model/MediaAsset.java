package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 媒体资产（schema: MediaAsset）。来源 URL → 我方存储 → 平台 media_id 回填，带处理状态与角色。
 * variant 自引用：variantOf + variantPurpose + variantLocale（LOCALIZED 预留，v1 不实现生成）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MediaAsset(
        String mediaId,
        String sourceUrl,
        String storageRef,
        MediaRole role,
        ProcessingState processingState,
        String variantOf,
        VariantPurpose variantPurpose,
        String variantLocale,
        Provenance provenance) {
}
