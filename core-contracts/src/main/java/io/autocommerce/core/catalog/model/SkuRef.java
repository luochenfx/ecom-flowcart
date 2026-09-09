package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * SKU 引用（schema: SkuRef，SPU.skus 列表元素）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SkuRef(String skuId) {
}
