package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 唯一货源引用（schema: SourceRef）。v1 一 SPU ↔ 一 source offer。
 * {@code fetchedAt} 为 ISO-8601 字符串（schema format: date-time）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SourceRef(String platform, String externalId, String url, String fetchedAt) {
}
