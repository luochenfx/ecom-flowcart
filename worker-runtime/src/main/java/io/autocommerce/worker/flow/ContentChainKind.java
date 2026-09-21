package io.autocommerce.worker.flow;

/**
 * 内容链链路类型（specs/0007 §4.3）：由**请求方声明**（国内 / 跨境），编排链内映射为
 * {@code ContentPlan}（见 {@link ContentPlanMapping}）。
 *
 * <p><b>为什么不按 {@code channelId} 推导</b>：若由渠道反推链路类型，编排层就必须知道 channel 的
 * 业务语义（要查渠道表、要知道"淘宝是国内平台"）——这是编排层对业务域的耦合。让请求方声明它本来
 * 就知道的事实，是零耦合的解法（CONTEXT.md「编排链」）。
 */
public enum ContentChainKind {

    /** 国内链：无需多语言回填，映射为 {@code ContentPlan.standard().without(i18n.backfill)}。 */
    DOMESTIC,

    /** 跨境链：多语言回填是硬依赖，映射为 {@code ContentPlan.standard()}。 */
    CROSS_BORDER
}
