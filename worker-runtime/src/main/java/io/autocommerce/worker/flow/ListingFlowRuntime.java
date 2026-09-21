package io.autocommerce.worker.flow;

/**
 * 编排链运行时常量与坐标推导（单一事实源）。与 {@code ContentRuntime} / {@code PublishRuntime} 同形态。
 *
 * <p>task queue 与 workflowId 的口径必须只有一处：worker 注册、client 启动、运维排查（按 id 搜
 * Temporal Web）三边都引用这里，避免"注册的队列名和启动时写的队列名不一致"这类只在运行时才暴露
 * 的装配错误。
 *
 * <h2>三链坐标口径一致（CONTEXT.md「链路坐标」）</h2>
 * 编排链 {@code fulfillment-{spuId}-{channelId}}、内容链 {@code content-{listingId}}、铺货链
 * {@code listing-{spuId}-{channelId}} 同源于同一 Listing（{@code listingId == listing-{spuId}-{channelId}}）。
 * 三处推导各自独立常量，但必须口径一致——不一致即边界早失败（{@code PublishWorkflowInput} 构造器
 * 机械校验铺货链那一段）。
 *
 * <p><b>Listing id 口径的单一事实源是</b>
 * {@link io.autocommerce.worker.publish.PublishRuntime#workflowIdFor(String, String)}
 * （{@code listing-{spuId}-{channelId}}，同模块既有类）。本类只管编排链自身的
 * {@code fulfillment-} workflowId；凡需要 Listing id 的编排侧代码（如装配 activity 定位刚落库文档）一律
 * 复用它，不再自带字面量副本——避免出现第 4 处口径分叉（Review R1 意见 1）。
 *
 * <p><b>独立 task queue</b>（{@link #TASK_QUEUE}）：编排链与两条子链分队列，互不抢占 worker 槽位。
 */
public final class ListingFlowRuntime {

    /** 编排链专用 task queue（与内容链 / 铺货链 / 订单链分队列）。 */
    public static final String TASK_QUEUE = "flow-task-queue";

    /** workflowId 前缀（对齐 ADR-0003 确定性业务键口径）。 */
    public static final String WORKFLOW_ID_PREFIX = "fulfillment-";

    private ListingFlowRuntime() {
    }

    /** {@code fulfillment-{spuId}-{channelId}}：确定性 workflowId，同 (spuId, channelId) 重复触发天然幂等。 */
    public static String workflowIdFor(String spuId, String channelId) {
        if (spuId == null || spuId.isBlank()) {
            throw new IllegalArgumentException("spuId 必填");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId 必填");
        }
        return WORKFLOW_ID_PREFIX + spuId + "-" + channelId;
    }
}
