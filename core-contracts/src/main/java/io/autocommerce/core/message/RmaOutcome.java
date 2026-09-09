package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 售后完结 outcome（schema: RmaClosedPayload.outcome enum）。
 */
public enum RmaOutcome {
    REFUNDED,
    PARTIAL_REFUNDED,
    CLOSED_NO_REFUND,
    ESCALATED
}
