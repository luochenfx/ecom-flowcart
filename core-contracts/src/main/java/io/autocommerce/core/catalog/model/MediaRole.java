package io.autocommerce.core.catalog.model;

/**
 * 媒体角色（schema: MediaRef.role / MediaAsset.role enum）。
 * 与 {@code schemas/product-catalog.schema.json} 枚举严格对齐，勿增减。
 */
public enum MediaRole {
    MAIN,
    GALLERY,
    DETAIL,
    VIDEO
}
