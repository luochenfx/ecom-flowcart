package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * workflow failed 错误类别（schema: SysWorkflowFailedPayload.error_type enum）。
 * 与 AdapterException.kind 同构（RETRYABLE_EXHAUSTED = 重试耗尽后的终态信号）。
 */
public enum WorkflowErrorType {
    RETRYABLE_EXHAUSTED,
    NON_RETRYABLE,
    AMBIGUOUS
}
