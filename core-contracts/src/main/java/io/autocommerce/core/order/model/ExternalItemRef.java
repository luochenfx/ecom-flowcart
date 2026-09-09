package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 平台侧商品/SKU 引用（schema: ExternalItemRef）。两字段均可空（平台差异）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExternalItemRef(String itemId, String skuId) {
}
