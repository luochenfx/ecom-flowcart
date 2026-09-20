package io.autocommerce.worker.publish;

/**
 * 铺货 activity 失败类型常量（Temporal {@code ApplicationFailure.type} 口径）。
 *
 * <p>这些类型名同时是 {@link PublishActivityOptions} 的 {@code doNotRetry} 名单与 {@link
 * PublishWorkflowImpl} 的失败分流依据——是"Adapter 错误三类（AC-4）→ Temporal 原语"映射的落点：
 * <ul>
 *   <li>{@link #RETRYABLE} → <b>可重试</b> ApplicationFailure：由 Activity RetryPolicy 指数退避，
 *       耗尽后 workflow 收口 FAILED；</li>
 *   <li>{@link #REJECTED} → <b>不可重试</b>（NonRetryable）：业务拒绝，workflow 收口 REJECTED；</li>
 *   <li>{@link #FAILED} → <b>不可重试</b>（NonRetryable）：意外未知（含非 AdapterException 缺陷），
 *       workflow 收口 FAILED。</li>
 * </ul>
 */
public final class PublishActivityErrors {

    /** 业务拒绝（Adapter NON_RETRYABLE）。 */
    public static final String REJECTED = "PublishRejected";
    /** 重试耗尽 / 意外未知。 */
    public static final String FAILED = "PublishFailed";
    /** 临时故障（Adapter RETRYABLE），可重试。 */
    public static final String RETRYABLE = "PublishRetryable";

    private PublishActivityErrors() {
    }
}
