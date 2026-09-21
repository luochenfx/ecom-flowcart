package io.autocommerce.worker.flow;

import io.temporal.worker.Worker;

import java.util.Objects;

/**
 * 编排链 worker 装配（Composition Root，ADR-0009）：把编排链的 workflow + activity 接到 Temporal
 * worker 上——业务模块（content / publish）不知道 Temporal，编排模块不知道平台细节，装配只发生在这里。
 *
 * <p>与 {@code ContentWorkerFactory} / {@code PublishWorkerFactory} 同例：测试走
 * {@code TestWorkflowEnvironment.newWorker(...)} + {@link #register}，生产走同一 {@link #register}。
 * 生产 worker 的 {@code start(...)} 属装配点（{@code app} Spring，specs/0007 §7.2）的后续票，不在本票。
 */
public final class ListingFlowWorkerFactory {

    private ListingFlowWorkerFactory() {
    }

    /** 在 worker 上注册编排链的 workflow 与 activity（不启动 factory，便于测试自有环境接管）。 */
    public static void register(Worker worker, ListingFlowActivities activities) {
        Objects.requireNonNull(worker, "worker 必填");
        Objects.requireNonNull(activities, "ListingFlowActivities 必填");
        worker.registerWorkflowImplementationTypes(ListingFlowWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
    }
}
