package io.autocommerce.worker.order;

/**
 * 订单链运行时常量与坐标推导（单一事实源）。
 *
 * <p>task queue 与 workflowId 的口径必须只有一处：worker 注册、client 启动、运维排查（按 id 搜
 * Temporal Web）三边都引用这里。
 */
public final class OrderRuntime {

    /** 订单链专用 task queue（与内容链 / 铺货链分队列：互不抢占 worker 槽位）。 */
    public static final String TASK_QUEUE = "order-task-queue";

    /** workflowId 前缀（对齐 ADR-0003 / specs/0003 §6 的确定性业务键口径）。 */
    public static final String WORKFLOW_ID_PREFIX = "order-";

    private OrderRuntime() {
    }

    /**
     * {@code order-{platform}-{orderNo}}：确定性 workflowId（specs/0003 §6 幂等第一级）。
     * 重复 start（webhook 重推 / 游标重读 / 手工重放）命中既有 execution，返回既有结果。
     */
    public static String workflowIdFor(String platform, String platformOrderNo) {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform 必填");
        }
        if (platformOrderNo == null || platformOrderNo.isBlank()) {
            throw new IllegalArgumentException("platformOrderNo 必填");
        }
        return WORKFLOW_ID_PREFIX + platform + "-" + platformOrderNo;
    }
}
