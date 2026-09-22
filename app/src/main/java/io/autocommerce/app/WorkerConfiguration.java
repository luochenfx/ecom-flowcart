package io.autocommerce.app;

import io.autocommerce.adapterhost.AdapterHost;
import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.order.purchase.PurchaseFulfillmentService;
import io.autocommerce.order.rma.RmaSyncService;
import io.autocommerce.order.store.OrderStore;
import io.autocommerce.publish.PublishService;
import io.autocommerce.worker.content.ContentWorkerFactory;
import io.autocommerce.worker.flow.ListingFlowWorkerFactory;
import io.autocommerce.worker.order.OrderWorkerFactory;
import io.autocommerce.worker.publish.PublishWorkerFactory;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.WorkerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Clock;

/**
 * 各链 {@code WorkerFactory} 装配（specs/0007 §7.2）：把编排链 + 三条子链的 workflow / activity 接到
 * Temporal worker 上并启动。
 *
 * <h2>条件与启停</h2>
 * <ul>
 *   <li><b>条件注解</b>：本类由 {@link Conditional @Conditional(WorkerRoleCondition.class)} 控制——
 *       {@code app.role} 不含 {@code worker} 时**整类不装配**，没有任何 worker bean（更不会启动）。
 *       用自定义 {@link WorkerRoleCondition} 而非 {@code @ConditionalOnProperty} 的原因见该类（逗号串）。</li>
 *   <li><b>就绪时启动</b>：四个 {@code WorkerFactory} bean 标 {@link Lazy @Lazy}——bean 定义在装配期
 *       注册（受条件门控），但**实例化（= 建 worker + 启动）推迟到首次解析**，即
 *       {@link WorkerStartup} 在 {@code ApplicationReadyEvent} 上遍历解析时。这样"启哪些 worker 由
 *       role 决定"这一事实同时落在条件（装配期）与就绪回调（启动期）。</li>
 * </ul>
 *
 * <p>workflowId / task queue 口径的单一事实源在各 {@code *Runtime}（见各 start 方法）。
 */
@Configuration
@Conditional(WorkerRoleCondition.class)
public class WorkerConfiguration {

    /** 编排链 worker（queue {@code flow-task-queue}）。 */
    @Lazy
    @Bean
    WorkerFactory listingFlowWorkerFactory(WorkflowClient client, CatalogStore store, Clock clock) {
        return ListingFlowWorkerFactory.start(client, store, clock);
    }

    /** 内容链 worker（queue {@code content-task-queue}）：AI Step 来源经 AdapterHost（specs/0007 §7.2）。 */
    @Lazy
    @Bean
    WorkerFactory contentWorkerFactory(WorkflowClient client, CatalogStore store, AdapterHost adapterHost) {
        return ContentWorkerFactory.start(client, store, adapterHost);
    }

    /** 铺货链 worker（queue {@code publish-task-queue}）。 */
    @Lazy
    @Bean
    WorkerFactory publishWorkerFactory(WorkflowClient client, PublishService publishService) {
        return PublishWorkerFactory.start(client, publishService);
    }

    /** 订单链 + 采购单链 worker（queue {@code order-task-queue}）。 */
    @Lazy
    @Bean
    WorkerFactory orderWorkerFactory(WorkflowClient client, OrderStore store,
                                     PurchaseFulfillmentService fulfillment, RmaSyncService rmaSync) {
        return OrderWorkerFactory.start(client, store, fulfillment, rmaSync);
    }
}
