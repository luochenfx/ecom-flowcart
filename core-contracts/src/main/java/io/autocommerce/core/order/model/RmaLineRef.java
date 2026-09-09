package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 售后行引用（schema: RmaLineRef，支持部分退款）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RmaLineRef(String orderLineId) {
}
