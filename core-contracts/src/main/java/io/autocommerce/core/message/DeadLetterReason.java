package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * DLQ 失败类别（schema: DeadLetter.reason）。
 * DESERIALIZATION/SCHEMA_VALIDATION = 永久毒消息（不重试直接 DLQ）；CONSUMER_EXCEPTION = delivery
 * 重试耗尽后进 DLQ；DELIVERY_EXPIRED = 投递过期。
 */
public enum DeadLetterReason {
    CONSUMER_EXCEPTION,
    DESERIALIZATION_FAILED,
    SCHEMA_VALIDATION_FAILED,
    DELIVERY_EXPIRED
}
