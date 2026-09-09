package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 时间戳对（schema: Timestamps）。ISO-8601 字符串。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Timestamps(String createdAt, String updatedAt) {
}
