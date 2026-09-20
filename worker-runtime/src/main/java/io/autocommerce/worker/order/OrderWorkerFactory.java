package io.autocommerce.worker.order;

import io.temporal.worker.Worker;

import java.util.Objects;

/**
 * 订单链 worker 装配（Composition Root，ADR-0009）：把订单链与采购单链的 workflow + activity 接到
 * Temporal worker 上——业务模块不知道 Temporal，编排模块不知道采购 / 物流细节，装配只发生在这里。
 *
 * <p>订单链（{@link OrderWorkflowImpl}）会把采购单作为 <b>child workflow</b>（{@link PurchaseWorkflowImpl}）
 * 起，故两者必须注册到同一 worker：本方法一次注册两条链。
 *
 * <p>与内容链同例：测试走 {@code TestWorkflowEnvironment.newWorker(...)} + {@link #register}，
 * 生产走同一 {@link #register}（真实 Adapter 能力由 adapter-host 装配后经
 * {@link OrderActivitiesImpl} / {@link PurchaseActivitiesImpl} 注入）。
 */
public final class OrderWorkerFactory {

    private OrderWorkerFactory() {
    }

    /** 在 worker 上注册订单链 + 采购单链的 workflow 与 activity（不启动 factory，便于测试自有环境接管）。 */
    public static void register(Worker worker, OrderActivities orderActivities,
                                PurchaseActivities purchaseActivities) {
        Objects.requireNonNull(worker, "worker 必填");
        Objects.requireNonNull(orderActivities, "OrderActivities 必填");
        Objects.requireNonNull(purchaseActivities, "PurchaseActivities 必填");
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class, PurchaseWorkflowImpl.class);
        worker.registerActivitiesImplementations(orderActivities, purchaseActivities);
    }
}
