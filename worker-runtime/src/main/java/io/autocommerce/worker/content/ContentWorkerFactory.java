package io.autocommerce.worker.content;

import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.content.ContentStepExecutor;
import io.autocommerce.content.spi.ContentAiStepProvider;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * 内容链 worker 装配（Composition Root，ADR-0009）：把"哪批 Step、哪个 store、哪些配置"接到
 * Temporal worker 上——业务模块不知道 Temporal，编排模块不知道 LLM 细节，装配只发生在这里。
 *
 * <p>两条装配路径共用 {@link #register(Worker, ContentChainActivities)}：
 * 生产走 {@link #start(WorkflowClient, CatalogStore, Map, Path)}（真实 provider，配置取环境变量），
 * 测试走 {@code TestWorkflowEnvironment.newWorker(...)} + {@code register(...)}（fake 端点，
 * 但走同一批真实 Step 与真实 OpenAI-compatible provider——不 mock 业务逻辑）。
 */
public final class ContentWorkerFactory {

    private ContentWorkerFactory() {
    }

    /** 在 worker 上注册内容链 workflow 与 activity（不启动 factory，便于测试自有环境接管）。 */
    public static void register(Worker worker, ContentChainActivities activities) {
        Objects.requireNonNull(worker, "worker 必填");
        Objects.requireNonNull(activities, "activities 必填");
        worker.registerWorkflowImplementationTypes(ContentWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
    }

    /**
     * 生产装配并启动内容链 worker。
     *
     * @param client    Temporal client（连接自托管 server，ADR-0002）
     * @param store     catalog 文档库（v1 = JsonFileCatalogStore；换成真库不影响本类）
     * @param config    LLM 配置表（容器部署传 {@code System.getenv()}；键位见 {@link ContentAiStepProvider}）
     * @param mediaRoot 媒体归档根目录（容器建议指向挂载卷）
     * @return 已启动的 WorkerFactory（调用方负责 shutdown 以优雅退出）
     */
    public static WorkerFactory start(WorkflowClient client, CatalogStore store,
                                      Map<String, String> config, Path mediaRoot) {
        Objects.requireNonNull(client, "WorkflowClient 必填");
        ContentAiStepProvider provider = ContentAiStepProvider.of(config, mediaRoot);
        ContentChainActivities activities = new ContentChainActivitiesImpl(store,
                new ContentStepExecutor(provider.steps(), Clock.systemUTC()),
                "ContentWorkflow",
                // 生产 worker 取 ActivityExecutionContext 派生坐标；start() 路径暂用"未启动"占位
                // ——本方法返回后调用方会用 register() 接管 activity 实例并接 ActivityExecutionContext，
                // 这里占位仅保证构造器不抛 NPE。
                new WorkflowCoordinates() {
                    @Override
                    public String workflowId() {
                        return "unbound-workflow-id";
                    }

                    @Override
                    public String runId() {
                        return "unbound-run-id";
                    }
                });
        WorkerFactory factory = WorkerFactory.newInstance(client);
        register(factory.newWorker(ContentRuntime.TASK_QUEUE), activities);
        factory.start();
        return factory;
    }
}
