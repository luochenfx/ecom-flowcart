package io.autocommerce.publish;

/**
 * 一次铺货步骤的处置结果（workflow 控制流依据；{@link PublishDecision#disposition()}）。
 *
 * <p>与 {@link PublishStatus}（投影列）刻意区分：投影只记终态/挂起态，而处置结果还要表达
 * "尚未发布，需继续 add"（{@link #NEEDS_ADD}）与"本次调用是幂等命中"（{@link #ALREADY_PUBLISHED}）
 * 这类编排信号。
 */
public enum PublishDisposition {

    /** 可重入检查：尚无 platform_item_id，需继续 add（workflow 继续）。 */
    NEEDS_ADD,
    /** 幂等命中：已有 platform_item_id 的事实复用了本行（不重复 add）。 */
    ALREADY_PUBLISHED,
    /** 本次调用新落库 PUBLISHED 事实（add 成功 / reconcile 回填 / 人工确认回填）。 */
    PUBLISHED,
    /** 超时歧义：已落库 AMBIGUOUS 事实，workflow 挂起等 signal（非终态）。 */
    AMBIGUOUS,
    /**
     * <b>外部已生效、本地未落库</b>：{@code add} 已成功返回，但 PUBLISHED 与 AMBIGUOUS 事实<b>都</b>写不下
     * （状态库整体不可用）——workflow 挂起等 signal（非终态），<b>不授权重铺</b>。
     *
     * <p>与 {@link #AMBIGUOUS} 的区别：本处置对应的事实<b>没有落库</b>，因此<b>不广播</b>
     * {@code listing.ambiguous}——否则等于让总线承载一条不存在的（未落库）事实，违反 AC-5 /
     * specs/0016 §0.3「总线只承载已落库事实，永不作 first write」。也<b>不得</b>归
     * {@link #RETRYABLE}（会经 Activity 退避重试 → 重跑 {@code add} → 重复铺货，违反 ADR-0003）。
     */
    SUSPENDED_UNRECORDED,
    /** 业务拒绝（Adapter NON_RETRYABLE）：已落库 REJECTED，workflow 以 failed 收尾。 */
    REJECTED,
    /** 可重试类重试耗尽 / 意外未知：已落库 FAILED，workflow 以 failed 收尾。 */
    FAILED,
    /**
     * 临时故障（Adapter RETRYABLE）：<b>未落库</b>，交由 Activity RetryPolicy 退避重试
     * （不带状态变更——重试可能成功，不应先写终态）。
     */
    RETRYABLE
}
