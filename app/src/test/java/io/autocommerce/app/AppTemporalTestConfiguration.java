package io.autocommerce.app;

import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * app 装配测试的 Temporal 替身配置（specs/0007 §7.3）：单测用 <b>in-process</b>
 * {@link TestWorkflowEnvironment}（纯 Java，无需 docker / 真 server），不连生产
 * {@code app.temporal.target}。
 *
 * <p>生产 {@code WorkflowClient}（{@link TemporalConfiguration}）仍会装配（gRPC 懒连、不阻塞），
 * 此处提供的 client 以 {@link Primary} 覆盖注入点，使 worker 启动 / REST 查询都走 in-process 服务
 * ——保证 {@code mvn clean test} 全绿且不因本票变慢（specs/0007 §7.3）。
 */
@TestConfiguration
public class AppTemporalTestConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    TestWorkflowEnvironment testWorkflowEnvironment() {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        environment.start();
        return environment;
    }

    @Bean
    @Primary
    WorkflowClient appTestWorkflowClient(TestWorkflowEnvironment environment) {
        return environment.getWorkflowClient();
    }
}
