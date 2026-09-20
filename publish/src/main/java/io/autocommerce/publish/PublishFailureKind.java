package io.autocommerce.publish;

/**
 * Adapter 错误 → publish 编排语义的归类（specs/0001 §5 / specs/0005 §6；AC-4 的落地口径）。
 *
 * <p>该归类是"错误三类 → Temporal 原语 + 终态编码"的单一事实源：
 * <ul>
 *   <li>{@link #RETRYABLE} ← {@code AdapterErrorKind.RETRYABLE}（限流/5xx/抖动）→ Activity RetryPolicy
 *       指数退避；耗尽后终态 {@link PublishStatus#FAILED}；</li>
 *   <li>{@link #REJECTED} ← {@code AdapterErrorKind.NON_RETRYABLE}（业务拒绝：资质/类目/参数非法）→
 *       不重试 + 终态 {@link PublishStatus#REJECTED}（{@code reason} = platform_code + 描述）；</li>
 *   <li>{@link #AMBIGUOUS} ← {@code AdapterErrorKind.AMBIGUOUS}（超时/断连不知是否生效）→ 不重发，
 *       走 reconcile / 保守挂起（非终态 {@link PublishStatus#AMBIGUOUS}）；</li>
 *   <li>{@link #UNEXPECTED} ← 非 {@code AdapterException} 的异常（= bug，specs/0005 §6）→ 按 NON_RETRYABLE
 *       收口（<b>不得</b>当临时故障重试到死），终态 {@link PublishStatus#FAILED}（"意外未知"，
 *       specs/0001 §3 状态表）。</li>
 * </ul>
 */
public enum PublishFailureKind {
    RETRYABLE,
    REJECTED,
    AMBIGUOUS,
    UNEXPECTED
}
