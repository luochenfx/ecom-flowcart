package io.autocommerce.worker.publish;

import io.autocommerce.core.catalog.model.Listing;

/**
 * 铺货链运行时常量与坐标推导（单一事实源）。
 *
 * <p>task queue 与 workflowId 的口径必须只有一处：worker 注册、client 启动、运维排查（按 id 搜
 * Temporal Web）三边都引用这里。
 *
 * <h2>workflowId 与 {@link Listing#listingId()} 的口径定稿（开放点 #1）</h2>
 * specs/0001 §2.2 定义 {@code workflowId = "listing-" + productId + "-" + channelId}。核实结论：
 * <b>{@code productId} 即 {@link Listing#spuId()}</b>——{@code Listing.listingId} 的 javadoc 称其
 * "为确定性 id（对 #11 workflowId 语义）"，而内容链的构造（{@code ListingDraftFactory}）产出的
 * 恰是 {@code listing-{spuId}-{channelId}}。于是三者重合：
 * {@code Listing.listingId == PublishRuntime.workflowIdFor(spuId, channelId) == 该 Listing 的 workflowId}。
 * 本方法即这一口径的单一事实源（对齐 {@code OrderRuntime.workflowIdFor} 的形状：常量 + 静态推导 +
 * 参数校验）；{@link PublishWorkflowInput} 在边界机械校验该不变量，避免两套口径静默分叉。
 */
public final class PublishRuntime {

    /** 铺货链专用 task queue（与内容链 / 订单链分队列：互不抢占 worker 槽位）。 */
    public static final String TASK_QUEUE = "publish-task-queue";

    /** workflowId 前缀（对齐 ADR-0003 / specs/0001 §2.2 的确定性业务键口径）。 */
    public static final String WORKFLOW_ID_PREFIX = "listing-";

    private PublishRuntime() {
    }

    /**
     * {@code listing-{spuId}-{channelId}}：确定性 workflowId（ADR-0003 / specs/0001 §2.2）。重复
     * start（重复铺货指令 / 重试 / 重放）派生同一 id，命中既有 execution 而非并发跑第二遍
     * （add 是花钱的外部副作用，不能重复执行）。
     *
     * @param productId 商品标识 = {@link Listing#spuId()}
     * @param channelId 目标渠道账号 id
     */
    public static String workflowIdFor(String productId, String channelId) {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId（= Listing.spuId）必填");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId 必填");
        }
        return WORKFLOW_ID_PREFIX + productId + "-" + channelId;
    }
}
