package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 来源属性快照（schema: Attribute，JSONB 键值容器，不开 EAV）。value 为 string|number|boolean
 * 任意一种，用 JsonNode 保真表达（序列化保持原始 JSON 类型）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Attribute(String key, JsonNode value, String unit) {
}
