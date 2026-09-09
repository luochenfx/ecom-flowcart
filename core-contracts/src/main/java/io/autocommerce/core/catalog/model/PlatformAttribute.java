package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 平台属性值（schema: PlatformAttribute，铺货时填写的目标平台类目属性）。
 * value 同 Attribute，JsonNode 保真。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PlatformAttribute(String platformAttrId, String key, JsonNode value) {
}
