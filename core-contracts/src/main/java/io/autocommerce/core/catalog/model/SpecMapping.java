package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 规格维度重映射（schema: SpecMapping）。canonical 规格维度名 → 平台属性 ID。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SpecMapping(String canonicalName, String platformAttrId) {
}
