package io.autocommerce.app;

import io.autocommerce.adapterhost.AdapterHost;
import io.autocommerce.api.FlowController;
import io.autocommerce.catalog.ingest.CatalogIngestService;
import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.catalog.store.PostgresCatalogStore;
import io.autocommerce.worker.content.ContentWorkflowLauncher;
import io.autocommerce.worker.flow.ListingFlowWorkflowLauncher;
import io.autocommerce.worker.order.OrderWorkflowLauncher;
import io.autocommerce.worker.publish.PublishWorkflowLauncher;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * app 生产装配的上文可达性 + role 启停（specs/0007 §10 步 7）：{@code @SpringBootTest} 加载真实
 * {@link FlowcartApplication} 上下文，断言基础设施 / launcher / REST 端点可达、worker 按 role 启动。
 *
 * <p>不连外部服务的两处替身（{@code mvn clean test} 无需 docker，specs/0007 §9.2）：
 * <ul>
 *   <li>datasource 的 Hikari 设 {@code initialization-fail-timeout=-1}（建池不校验连接）、
 *       {@code spring.flyway.enabled=false}（不迁移）——故生产 {@link PostgresCatalogStore} bean 能被
 *       装配（证明装配成立），但不触真库；</li>
 *   <li>Temporal 走 in-process（{@link AppTemporalTestConfiguration}）。</li>
 * </ul>
 */
@SpringBootTest(classes = FlowcartApplication.class, properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "app.role=api,worker,scheduler",
        "app.temporal.target=127.0.0.1:7233",
        "app.data-root=target/app-test-data",
        "app.media-root=target/app-test-media"
})
@Import(AppTemporalTestConfiguration.class)
class AppContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CatalogStore catalogStore;

    @Autowired
    private AdapterHost adapterHost;

    @Autowired
    private WorkflowClient workflowClient;

    @Test
    void assemblesInfrastructureBeans() {
        assertThat(catalogStore).as("CatalogStore = Postgres 实现").isInstanceOf(PostgresCatalogStore.class);
        assertThat(adapterHost.platforms()).as("AdapterHost 经 SPI 发现平台")
                .contains("1688", "fake-sales");
        assertThat(workflowClient).isNotNull();
    }

    @Test
    void assemblesCollectorAndAllLaunchers() {
        assertThat(context.getBean(CatalogIngestService.class)).isNotNull();
        assertThat(context.getBean(ListingFlowWorkflowLauncher.class)).isNotNull();
        assertThat(context.getBean(ContentWorkflowLauncher.class)).isNotNull();
        assertThat(context.getBean(PublishWorkflowLauncher.class)).isNotNull();
        assertThat(context.getBean(OrderWorkflowLauncher.class)).isNotNull();
    }

    @Test
    void restEndpointReachableViaScanBasePackages() {
        // #74 陷阱 1：FlowController 在 io.autocommerce.api，默认扫描根 io.autocommerce.app 扫不到。
        // 显式 scanBasePackages="io.autocommerce" 后必须可达，否则 REST 端点在生产装配下静默缺失。
        assertThat(context.getBean(FlowController.class)).isNotNull();
    }

    @Test
    void workerRoleStartsAllChainWorkers() {
        assertThat(context.getBeanNamesForType(WorkerFactory.class))
                .containsExactlyInAnyOrder("listingFlowWorkerFactory", "contentWorkerFactory",
                        "publishWorkerFactory", "orderWorkerFactory");
    }
}
