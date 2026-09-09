package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 订单行引用（schema: LineRef，Order.lines 元素）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LineRef(String orderLineId) {
}
