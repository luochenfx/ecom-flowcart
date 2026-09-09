package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Listing 图片（schema: ListingImage）。引用 SPU 资产或覆盖图；platformMediaId 铺货后回填。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ListingImage(String mediaId, MediaRole role, String platformMediaId) {
}
