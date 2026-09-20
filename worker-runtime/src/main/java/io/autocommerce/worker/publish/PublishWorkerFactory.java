package io.autocommerce.worker.publish;

import io.temporal.worker.Worker;

import java.util.Objects;

/**
 * 铺货链 worker 装配（Composition Root，ADR-0009）：把铺货链的 workflow + activity 接到 Temporal
 * worker 上——业务模块（publish）不知道 Temporal，编排模块不知道平台细节，装配只发生在这里。
 *
 * <p>与内容链 / 订单链同例：测试走 {@code TestWorkflowEnvironment.newWorker(...)} + {@link #register}，
 * 生产走同一 {@link #register}（真实 Adapter 能力由 adapter-host 装配后经
 * {@link PublishActivitiesImpl} 注入）。
 */
public final class PublishWorkerFactory {

    private PublishWorkerFactory() {
    }

    /** 在 worker 上注册铺货链的 workflow 与 activity（不启动 factory，便于测试自有环境接管）。 */
    public static void register(Worker worker, PublishActivities activities) {
        Objects.requireNonNull(worker, "worker 必填");
        Objects.requireNonNull(activities, "PublishActivities 必填");
        worker.registerWorkflowImplementationTypes(PublishWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
    }
}
