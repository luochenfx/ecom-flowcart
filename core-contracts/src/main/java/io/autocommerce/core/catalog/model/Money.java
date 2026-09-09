package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 金额（schema: catalog Money def）。amount 为 decimal string，避免浮点。
 * 注意：order 域的 Money.amount 是 number（见 order.model.Money），两域按各自 schema 保持独立类型。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Money(String amount, String currency) {
}
