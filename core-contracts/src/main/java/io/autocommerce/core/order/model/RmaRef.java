package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 售后单引用（schema: RmaRef，Order.rmas 元素）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RmaRef(String rmaId) {
}
