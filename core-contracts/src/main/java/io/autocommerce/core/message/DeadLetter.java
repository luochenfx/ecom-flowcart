package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * DLQ 包裹（schema: DeadLetter）。完整原 envelope 不丢 payload（可重放 = 解包原样重投）。
 * DLQ = 隔离舱 + 信号源，绝不自愈（ADR-0001）；业务失败不进 DLQ（workflow RetryPolicy/Saga 管）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeadLetter(
        Envelope originalMessage,
        DeadLetterReason reason,
        DeadLetterError error,
        Integer retryCount,
        String deadAt,
        String queue) {
}
