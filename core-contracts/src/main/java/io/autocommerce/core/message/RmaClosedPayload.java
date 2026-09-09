package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * rma.closed 事件负载（schema: RmaClosedPayload）。售后单完结（outcome 摘要，明细回读）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RmaClosedPayload(String rmaId, String orderId, RmaOutcome outcome) {
}
