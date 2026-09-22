package io.autocommerce.app;

import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TemporalConfiguration} 生产装配的"连真 server"证据（specs/0007 §7.3）：断言
 * {@link WorkflowClient} 由 {@code app.temporal.target} / {@code app.temporal.namespace} 构造——
 * 即生产路径连的是自托管真 server（{@code temporal:7233}），而非 in-process test service。
 *
 * <p>用 {@link ApplicationContextRunner} 只装配 {@link TemporalConfiguration}（不拉全上下文），
 * gRPC 通道懒连、构造 client 不触网，故本测不依赖 docker / 真 server。
 */
class TemporalConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TemporalConfiguration.class)
            .withBean(AppProperties.class, TemporalConfigurationTest::properties);

    private static AppProperties properties() {
        AppProperties properties = new AppProperties();
        properties.getTemporal().setTarget("temporal:7233");
        properties.getTemporal().setNamespace("default");
        return properties;
    }

    @Test
    void workflowClientBindsConfiguredRealServerTarget() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(WorkflowClient.class);
            WorkflowClient client = context.getBean(WorkflowClient.class);
            assertThat(client.getWorkflowServiceStubs().getOptions().getTarget())
                    .as("生产装配连 compose 的真 server，非 in-process")
                    .isEqualTo("temporal:7233");
            assertThat(client.getOptions().getNamespace()).isEqualTo("default");
        });
    }
}
