package io.autocommerce.app;

import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.publish.PublishService;
import io.autocommerce.worker.flow.ListingFlowWorkflowLauncher;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * role 不含 {@code worker} 时的启停语义（specs/0007 §7.2 AC「role 不含 worker 时不启动」）：
 * 以 {@code app.role=api} 加载上下文，断言 <b>零 worker bean</b>（{@link WorkerRoleCondition} 使
 * {@link WorkerConfiguration} / {@link WorkerServiceConfiguration} 整类不装配），但 REST 侧
 * （launcher / 采集 / catalog）仍装配——即"只起 REST、不起 worker"的部署形态成立。
 */
@SpringBootTest(classes = FlowcartApplication.class, properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "app.role=api",
        "app.temporal.target=127.0.0.1:7233",
        "app.data-root=target/app-test-data-no-worker",
        "app.media-root=target/app-test-media"
})
@Import(AppTemporalTestConfiguration.class)
class AppContextWithoutWorkerRoleTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void noWorkerBeansWhenRoleExcludesWorker() {
        assertThat(context.getBeanNamesForType(WorkerFactory.class))
                .as("app.role=api → 不装配任何 WorkerFactory（更不启动）").isEmpty();
    }

    @Test
    void workerOnlyServicesAbsentWhenRoleExcludesWorker() {
        assertThat(context.getBeanNamesForType(PublishService.class)).isEmpty();
    }

    @Test
    void restSideStillAssembled() {
        assertThat(context.getBean(CatalogStore.class)).isNotNull();
        assertThat(context.getBean(ListingFlowWorkflowLauncher.class)).isNotNull();
    }
}
