package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 供应商发货物流（schema: Tracking，logistics.trace 取回）。各字段均可空。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Tracking(String company, String trackingNo, String status, String url) {
}
