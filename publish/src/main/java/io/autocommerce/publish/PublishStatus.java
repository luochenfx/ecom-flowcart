package io.autocommerce.publish;

/**
 * Listing 铺货状态（投影 status 列语义，specs/0001 §3 四态表）。
 *
 * <p>执行真相在 Temporal Event History；本枚举只承载"看板/列表可读的最新态"。
 * 终态编码（ADR-0003 / specs/0001 §3）：
 * <ul>
 *   <li>{@link #PUBLISHED} → workflow <b>completed</b>（终态；误触重复提交被 AlreadyStarted 拒绝）；</li>
 *   <li>{@link #REJECTED} / {@link #FAILED} → workflow <b>failed</b>（二者都以 failed 收尾以启用
 *       {@code AllowDuplicateFailedOnly}，靠错误类型 + {@code reason} 区分，见
 *       {@link PublishFailureKind}）；</li>
 *   <li>{@link #AMBIGUOUS} → workflow <b>挂起</b>（<b>非终态</b>，超时/断连不知是否生效，等
 *       reconcile / 人工 signal）。</li>
 * </ul>
 */
public enum PublishStatus {

    /** add 成功，已回填 platform_item_id；workflow completed。 */
    PUBLISHED,
    /** 超时/断连不知是否生效；workflow 挂起等 signal（非终态）。 */
    AMBIGUOUS,
    /** 业务拒绝（Adapter NON_RETRYABLE）；workflow failed。 */
    REJECTED,
    /** 可重试类错误重试耗尽 / 意外未知（含非 AdapterException 缺陷）；workflow failed。 */
    FAILED;

    /** 是否终态：PUBLISHED / REJECTED / FAILED 为终态；AMBIGUOUS 为唯一非终态。 */
    public boolean terminal() {
        return this != AMBIGUOUS;
    }
}
