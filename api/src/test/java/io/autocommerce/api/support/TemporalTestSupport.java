package io.autocommerce.api.support;

import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

/**
 * in-process Temporal 测试基座（{@code TestWorkflowEnvironment}，纯 Java，无需 docker / Temporal CLI）：
 * 一个 JVM 内共享的单例环境，并在独立的探针 task queue 上注册 {@link ProbeFlowImpl}。
 *
 * <p>查询端点按 workflowId 反查 execution 状态——因此需要**真实服务端**（不能是内存假象），
 * 这正是 {@code TestWorkflowEnvironment}（自带 in-process test service）的用途。
 */
public final class TemporalTestSupport {

    /** 探针专用队列：与生产 {@code flow-task-queue} 隔离，避免污染"无 zombie 链"断言。 */
    public static final String PROBE_TASK_QUEUE = "api-probe-queue";

    private static TestWorkflowEnvironment environment;

    private TemporalTestSupport() {
    }

    /** 懒启动（幂等）并返回共享环境。 */
    public static synchronized TestWorkflowEnvironment environment() {
        if (environment == null) {
            TestWorkflowEnvironment created = TestWorkflowEnvironment.newInstance();
            Worker worker = created.newWorker(PROBE_TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(ProbeFlowImpl.class);
            created.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    created.shutdownNow();
                } catch (RuntimeException ignored) {
                    // JVM 退出阶段：关闭失败无需处理
                }
            }, "api-temporal-test-shutdown"));
            environment = created;
        }
        return environment;
    }

    public static WorkflowClient client() {
        return environment().getWorkflowClient();
    }
}
