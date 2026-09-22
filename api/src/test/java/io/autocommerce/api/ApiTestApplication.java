package io.autocommerce.api;

import io.autocommerce.api.support.ScriptedOfferFetch;
import io.autocommerce.api.support.TemporalTestSupport;
import io.autocommerce.catalog.ingest.CatalogIngestService;
import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.catalog.store.JsonFileCatalogStore;
import io.autocommerce.worker.flow.ListingFlowWorkflowLauncher;
import io.temporal.client.WorkflowClient;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code api} 模块测试侧**最小装配根**（specs/0007 §10 步 6）：
 *
 * <p>#74（{@code app} 的 Spring 装配）尚未落地，{@code api} 测试**不能依赖 {@code app}**。本类提供
 * {@code @SpringBootTest} 所需的可启动上下文——由测试自备 {@link CatalogIngestService} /
 * {@link ListingFlowWorkflowLauncher} / {@link WorkflowClient} 三个 bean，{@code @ComponentScan}
 * （{@code @SpringBootApplication} 自带，基准包 = {@code io.autocommerce.api}）发现 api 的
 * controller / service / advice。
 *
 * <p>无需排除数据源自动装配：Spring Boot 4 把 JDBC 自动配置拆到独立模块（仅
 * {@code spring-boot-starter-jdbc} 引入），本模块 classpath 上只有 {@code api → catalog} 传递来的
 * 裸 {@code spring-jdbc}，不会触发 {@code DataSourceAutoConfiguration}。
 */
@SpringBootApplication
public class ApiTestApplication {

    @Bean
    CatalogStore catalogStore() {
        try {
            Path root = Files.createTempDirectory("api-catalog-test");
            return new JsonFileCatalogStore(root);
        } catch (IOException e) {
            throw new UncheckedIOException("创建测试 catalog 目录失败", e);
        }
    }

    /** 外部边界（1688 offer fetch）的手写 test fake——**不是 mock 框架**（本仓测试哲学）。 */
    @Bean
    ScriptedOfferFetch offerFetch() {
        return new ScriptedOfferFetch();
    }

    @Bean
    CatalogIngestService catalogIngestService(ScriptedOfferFetch offerFetch, CatalogStore catalogStore) {
        return new CatalogIngestService(offerFetch, catalogStore);
    }

    @Bean
    WorkflowClient workflowClient() {
        return TemporalTestSupport.client();
    }

    @Bean
    ListingFlowWorkflowLauncher flowLauncher(WorkflowClient client) {
        return ListingFlowWorkflowLauncher.standard(client);
    }
}
