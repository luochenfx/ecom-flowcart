package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 结构化规格值（schema: SpecValue）。canonical 名/值（跨平台稳定语义，如 color=red / size=M）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SpecValue(String name, String value) {
}
