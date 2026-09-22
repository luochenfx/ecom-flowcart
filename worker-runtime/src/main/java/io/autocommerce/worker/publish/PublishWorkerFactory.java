package io.autocommerce.worker.publish;

import io.autocommerce.publish.PublishService;
import io.autocommerce.worker.event.NoopEventPublisher;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

import java.util.Objects;

/**
 * 铺货链 worker 装配（Composition Root，ADR-0009）：把铺货链的 workflow + activity 接到 Temporal
 * worker 上——业务模块（publish）不知道 Temporal，编排模块不知道平台细节，装配只发生在这里。
 *
 * <p>与内容链 / 订单链同例：测试走 {@code TestWorkflowEnvironment.newWorker(...)} + {@link #register}，
 * 生产走 {@link #start(WorkflowClient, PublishService)}（真实 Adapter 能力由 adapter-host 装配后经
 * {@link PublishActivitiesImpl} 注入）。
 */
public final class PublishWorkerFactory {

    private PublishWorkerFactory() {
    }

    /** 在 worker 上注册铺货链的 workflow 与 activity（不启动 factory，便于测试自有环境接管）。 */
    public static void register(Worker worker, PublishActivities activities) {
        Objects.requireNonNull(worker, "worker 必填");
        Objects.requireNonNull(activities, "activities 必填");
        worker.registerWorkflowImplementationTypes(PublishWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
    }

    /**
     * 生产装配并启动铺货链 worker（specs/0007 §7.2）。
     *
     * <p>EventPublisher 取 {@link NoopEventPublisher}：v1 事件总线接入（RabbitMQ）尚未落地
     * （specs/0007 §12），落库后广播走 no-op，不改变状态机语义。
     *
     * @param client  Temporal client（连接自托管 server，ADR-0002）
     * @param service 铺货域服务（状态机 + 落库 + 外部 {@code PublishCapability} 调用）
     * @return 已启动的 WorkerFactory（调用方负责 shutdown 以优雅退出）
     */
    public static WorkerFactory start(WorkflowClient client, PublishService service) {
        Objects.requireNonNull(client, "WorkflowClient 必填");
        Objects.requireNonNull(service, "PublishService 必填");
        PublishActivities activities = new PublishActivitiesImpl(
                service, new NoopEventPublisher(), PublishWorkflow.class.getSimpleName());
        WorkerFactory factory = WorkerFactory.newInstance(client);
        register(factory.newWorker(PublishRuntime.TASK_QUEUE), activities);
        factory.start();
        return factory;
    }
}
