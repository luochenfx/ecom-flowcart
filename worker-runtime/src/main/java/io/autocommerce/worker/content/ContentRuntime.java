package io.autocommerce.worker.content;

/**
 * 内容链运行时常量与坐标推导（单一事实源）。
 *
 * <p>task queue 与 workflowId 的口径必须只有一处：worker 注册、client 启动、运维排查（按 id 搜
 * Temporal Web）三边都引用这里，避免"注册的队列名和启动时写的队列名不一致"这类只在运行时才暴露
 * 的装配错误。
 */
public final class ContentRuntime {

    /** 内容链专用 task queue（与铺货 / 订单链分队列：互不抢占 worker 槽位）。 */
    public static final String TASK_QUEUE = "content-task-queue";

    /** workflowId 前缀（对齐 ADR-0003 确定性业务键口径）。 */
    public static final String WORKFLOW_ID_PREFIX = "content-";

    private ContentRuntime() {
    }

    /** {@code content-{listingId}}：确定性 workflowId，同 Listing 重复触发天然幂等。 */
    public static String workflowIdFor(String listingId) {
        if (listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("listingId 必填");
        }
        return WORKFLOW_ID_PREFIX + listingId;
    }
}
