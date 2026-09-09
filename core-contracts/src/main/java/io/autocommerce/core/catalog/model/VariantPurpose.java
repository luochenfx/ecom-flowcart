package io.autocommerce.core.catalog.model;

/**
 * 媒体变体用途（schema: MediaAsset.variant_purpose enum）。
 * LOCALIZED = AI 生成图内文本本地化变体（独立新图，非文本翻译）——v1 结构预留，生成任务未实现。
 */
public enum VariantPurpose {
    LOCALIZED,
    GENERATED,
    CROPPED
}
