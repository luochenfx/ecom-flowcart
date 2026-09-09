package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.math.BigDecimal;

/**
 * 金额（schema: order Money def）。amount 为 number（小数按平台精度），用 BigDecimal 保真。
 * 与 catalog.model.Money（amount 为 decimal string）按各自 schema 独立，勿混用。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Money(BigDecimal amount, String currency) {
}
