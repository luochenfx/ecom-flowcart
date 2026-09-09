package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * DeadLetterEnvelope（schema: DeadLetterEnvelope）。DLQ 消息 = 重放素材。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeadLetterEnvelope(DeadLetter deadLetter) {
}
