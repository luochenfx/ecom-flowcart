package io.autocommerce.core.contract;

/**
 * Adapter 错误三类（ADR-0007 / specs/0005 §6，对齐 #11 铺货幂等）。
 * workflow 层失败决策零翻译：RetryPolicy ← RETRYABLE、Saga/NonRetryableErrorTypes ← NON_RETRYABLE、
 * reconcile ← AMBIGUOUS。
 */
public enum AdapterErrorKind {
    /** 限流 429 / 5xx / 网络抖动 → Activity RetryPolicy（retryableAfter 供退避参考） */
    RETRYABLE,
    /** 业务拒绝（资质/类目违规/参数非法）→ NonRetryableErrorTypes + Saga；platformCode + message 落投影 */
    NON_RETRYABLE,
    /** 超时/连接断开，不知是否生效 → 不重发，触发 reconcile 路径 */
    AMBIGUOUS
}
