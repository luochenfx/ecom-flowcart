package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;

/**
 * 商品目录文档（schema: product-catalog.schema.json 根对象）。
 * 校验/传输以整文档为单位；schemaVersion 固定 "0.1.0"（schema const，勿改）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductCatalog(
        String schemaVersion,
        List<Spu> spus,
        List<Sku> skus,
        List<Listing> listings,
        List<MediaAsset> mediaAssets) {
}
