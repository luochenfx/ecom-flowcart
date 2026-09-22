package io.autocommerce.app;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporal 生产装配（specs/0007 §7.2/§7.3）：连 <b>compose 的自托管真 server</b>
 * （{@code app.temporal.target}，默认 {@code temporal:7233}），<b>非</b> in-process test service。
 *
 * <p>本仓未引入 temporal-spring-boot-starter（不在依赖管理里），故按 spec §7.2「Spring 配置类在 app
 * 内定义 bean」手工构造 {@link WorkflowServiceStubs} + {@link WorkflowClient}。配置项从
 * {@link AppProperties.Temporal} 取（namespace 默认 {@code default}）。
 *
 * <p><b>单测不走本路径</b>：in-process 的 {@code TestWorkflowEnvironment} 由测试自备 client
 * （见 {@code AppContextTest}），本路径只在生产 / e2e（真 server）生效——保证单测不因本票连真 server
 * 而变慢（specs/0007 §7.3）。
 *
 * <p>连接是 gRPC 懒建的：构造 client 不阻塞连接，首个 RPC 才真正建链——故上下文装配阶段不依赖
 * server 在线（失败在首次调用时暴露，而非启动即崩）。
 */
@Configuration
public class TemporalConfiguration {

    /** gRPC 通道资源：容器关闭时 {@code shutdown()} 优雅释放。 */
    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs workflowServiceStubs(AppProperties properties) {
        return WorkflowServiceStubs.newInstance(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.getTemporal().getTarget())
                .build());
    }

    /** Temporal client（连自托管 server，ADR-0002）：各 launcher / worker 的注入点。 */
    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, AppProperties properties) {
        return WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder()
                .setNamespace(properties.getTemporal().getNamespace())
                .build());
    }
}
