package io.autocommerce.worker.content;

import io.autocommerce.adapterhost.AdapterHost;
import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.content.ContentStepExecutor;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

import java.time.Clock;
import java.util.Objects;

/**
 * 内容链 worker 装配（Composition Root，ADR-0009）：把"哪批 Step、哪个 store、哪些配置"接到
 * Temporal worker 上——业务模块不知道 Temporal，编排模块不知道 LLM 细节，装配只发生在这里。
 *
 * <p>两条装配路径共用 {@link #register(Worker, ContentChainActivities)}：
 * 生产走 {@link #start(WorkflowClient, CatalogStore, AdapterHost)}，
 * 测试走 {@code TestWorkflowEnvironment.newWorker(...)} + {@code register(...)}（fake 端点，
 * 但走同一批真实 Step 与真实 OpenAI-compatible provider——不 mock 业务逻辑）。
 *
 * <h2>AI Step 来源统一经 {@code AdapterHost}（specs/0007 §7.1/§7.2）</h2>
 * 生产启动路径的 Step 清单<b>不再</b>在本类内用 {@code ContentAiStepProvider.of(config, mediaRoot)}
 * 自行构造，而统一取自 SPI 装配根 {@link AdapterHost#aiSteps()}——即 classpath 上
 * {@code META-INF/services/io.autocommerce.core.step.AiStepProvider} 声明的 provider 汇总结果。
 * 这与 {@code adapter-host} 把 Adapter / AI Step 一并定为装配点的决议一致（「classpath 上有什么
 * 就能发现什么」），也使"新增 Step 不需要改装配代码"。provider 的 LLM 配置 / 媒体根目录由该 SPI
 * 实现自身从其环境（环境变量）解析（见 {@code ContentAiStepProvider} 无参构造）。
 *
 * <p><b>{@code CatalogStore} 参数保持不变</b>——本票只把 Step 来源从"本类自造"改为"经装配根注入"，
 * 不触存储端口（specs/0007 §7.2 AC）。
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
     * @param client      Temporal client（连接自托管 server，ADR-0002）
     * @param store       catalog 文档库（装配点注入；本票后生产实现 = {@code PostgresCatalogStore}）
     * @param adapterHost SPI 装配根（{@link AdapterHost#aiSteps()} 提供本链要跑的 AI Step 清单）
     * @return 已启动的 WorkerFactory（调用方负责 shutdown 以优雅退出）
     */
    public static WorkerFactory start(WorkflowClient client, CatalogStore store, AdapterHost adapterHost) {
        Objects.requireNonNull(client, "WorkflowClient 必填");
        Objects.requireNonNull(adapterHost, "AdapterHost 必填");
        ContentChainActivities activities = new ContentChainActivitiesImpl(store,
                new ContentStepExecutor(adapterHost.aiSteps(), Clock.systemUTC()),
                ContentWorkflow.class.getSimpleName(),
                // 坐标取真实值：activity 执行时从 ActivityExecutionContext 拿 workflowId / runId
                // （惰性求值，见 WorkflowCoordinates#fromActivityExecutionContext）。
                // 早期版本此处传字面量占位、并声称"调用方会接管 activity 实例再接上下文"——没有这样的调用方。
                WorkflowCoordinates.fromActivityExecutionContext());
        WorkerFactory factory = WorkerFactory.newInstance(client);
        register(factory.newWorker(ContentRuntime.TASK_QUEUE), activities);
        factory.start();
        return factory;
    }
}
