package io.autocommerce.worker.flow;

import io.autocommerce.catalog.store.CatalogStore;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

import java.time.Clock;
import java.util.Objects;

/**
 * 编排链 worker 装配（Composition Root，ADR-0009）：把编排链的 workflow + activity 接到 Temporal
 * worker 上——业务模块（content / publish）不知道 Temporal，编排模块不知道平台细节，装配只发生在这里。
 *
 * <p>与 {@code ContentWorkerFactory} / {@code PublishWorkerFactory} 同例：测试走
 * {@code TestWorkflowEnvironment.newWorker(...)} + {@link #register}，生产走
 * {@link #start(WorkflowClient, CatalogStore, Clock)}（{@link #register} 复用同一注册）。
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

    /**
     * 生产装配并启动编排链 worker（specs/0007 §7.2）。
     *
     * @param client Temporal client（连接自托管 server，ADR-0002）
     * @param store  catalog 文档库（装配Listing / 内容就绪断言读写的对象）
     * @param clock  装配 Listing 的时间来源
     * @return 已启动的 WorkerFactory（调用方负责 shutdown 以优雅退出）
     */
    public static WorkerFactory start(WorkflowClient client, CatalogStore store, Clock clock) {
        Objects.requireNonNull(client, "WorkflowClient 必填");
        Objects.requireNonNull(store, "CatalogStore 必填");
        Objects.requireNonNull(clock, "Clock 必填");
        WorkerFactory factory = WorkerFactory.newInstance(client);
        register(factory.newWorker(ListingFlowRuntime.TASK_QUEUE),
                new ListingFlowActivitiesImpl(store, clock));
        factory.start();
        return factory;
    }
}
