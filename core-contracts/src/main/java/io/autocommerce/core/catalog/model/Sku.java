package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;

/**
 * SKU（master 规格变体）—— schema: Sku。spuId/specs/costPrice/provenance 必填；
 * barcode 可空、images 可选变体图、sourceSkuId 为 1688 skuMap 原始 skuId。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Sku(
        String skuId,
        String spuId,
        List<SpecValue> specs,
        Money costPrice,
        String barcode,
        List<MediaRef> images,
        String sourceSkuId,
        Provenance provenance) {
}
