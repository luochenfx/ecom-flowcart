package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Listing SKU（schema: ListingSku）。引用 canonical SKU + 平台售价（成本 + 加价策略产物）；
 * enabled=false 表示该规格不铺（可只铺部分规格）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ListingSku(String skuId, Money price, Boolean enabled) {
}
