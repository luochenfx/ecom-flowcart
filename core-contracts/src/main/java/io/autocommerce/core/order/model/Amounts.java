package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 金额聚合（schema: Amounts）。全部可选；粒度以平台 API 能给的为准，缺口由 platform_raw 兜底。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Amounts(
        Money goodsAmount,
        Money shippingAmount,
        Money discountAmount,
        Money paymentAmount,
        String currency) {
}
