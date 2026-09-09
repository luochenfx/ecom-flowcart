package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 媒体引用（schema: MediaRef，SPU/SKU 图片列表元素）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MediaRef(String mediaId, MediaRole role) {
}
