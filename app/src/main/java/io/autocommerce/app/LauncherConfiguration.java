package io.autocommerce.app;

import io.autocommerce.catalog.ingest.CatalogIngestService;
import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.worker.content.ContentWorkflowLauncher;
import io.autocommerce.worker.flow.ListingFlowWorkflowLauncher;
import io.autocommerce.worker.order.OrderWorkflowLauncher;
import io.autocommerce.worker.publish.PublishWorkflowLauncher;
import io.temporal.client.WorkflowClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 采集入口与各链 launcher 装配（specs/0007 §7.2）：{@code WorkflowClient} 注入各 workflow 启动器，
 * 采集服务接 {@code OfferFetchCapability} + {@code CatalogStore}。
 *
 * <p>这里装配的是四类 launcher：编排链（{@link ListingFlowWorkflowLauncher}）+ 三条子链
 * （内容 {@link ContentWorkflowLauncher} / 铺货 {@link PublishWorkflowLauncher} / 订单
 * {@link OrderWorkflowLauncher}）。它们由 {@code api} 层的 REST 端点与 worker 侧消费。
 */
@Configuration
public class LauncherConfiguration {

    /** 采集入口（纯 Java 服务，specs/0007 §3）：REST 入口同步调用。 */
    @Bean
    CatalogIngestService catalogIngestService(OfferFetchCapability offerFetch, CatalogStore catalogStore) {
        return new CatalogIngestService(offerFetch, catalogStore);
    }

    /** 编排链启动器（client 侧入口）。 */
    @Bean
    ListingFlowWorkflowLauncher listingFlowWorkflowLauncher(WorkflowClient client) {
        return ListingFlowWorkflowLauncher.standard(client);
    }

    /** 内容链启动器。 */
    @Bean
    ContentWorkflowLauncher contentWorkflowLauncher(WorkflowClient client) {
        return ContentWorkflowLauncher.standard(client);
    }

    /** 铺货链启动器。 */
    @Bean
    PublishWorkflowLauncher publishWorkflowLauncher(WorkflowClient client) {
        return PublishWorkflowLauncher.standard(client);
    }

    /** 订单链启动器。 */
    @Bean
    OrderWorkflowLauncher orderWorkflowLauncher(WorkflowClient client) {
        return OrderWorkflowLauncher.standard(client);
    }
}
