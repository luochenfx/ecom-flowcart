package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 对象级血缘（schema: Provenance）。createdByStep 必填；updatedByStep 有更新时出现；
 * parentRef 为父对象引用（Listing→SPU/SKU、SPU→source offer）。时间字段 ISO-8601 字符串。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Provenance(
        ProvenanceStep createdByStep,
        ProvenanceStep updatedByStep,
        String parentRef,
        String createdAt,
        String updatedAt) {
}
