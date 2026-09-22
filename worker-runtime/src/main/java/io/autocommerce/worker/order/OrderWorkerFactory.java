package io.autocommerce.worker.order;

import io.autocommerce.order.purchase.PurchaseFulfillmentService;
import io.autocommerce.order.rma.RmaSyncService;
import io.autocommerce.order.store.OrderStore;
import io.autocommerce.worker.event.NoopEventPublisher;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

import java.util.Objects;

/**
 * 订单链 worker 装配（Composition Root，ADR-0009）：把订单链与采购单链的 workflow + activity 接到
 * Temporal worker 上——业务模块不知道 Temporal，编排模块不知道采购 / 物流细节，装配只发生在这里。
 *
 * <p>订单链（{@link OrderWorkflowImpl}）会把采购单作为 <b>child workflow</b>（{@link PurchaseWorkflowImpl}）
 * 起，故两者必须注册到同一 worker：本方法一次注册两条链。
 *
 * <p>与内容链同例：测试走 {@code TestWorkflowEnvironment.newWorker(...)} + {@link #register}，
 * 生产走 {@link #start}（真实 Adapter 能力由 adapter-host 装配后经
 * {@link OrderActivitiesImpl} / {@link PurchaseActivitiesImpl} 注入）。
 */
public final class OrderWorkerFactory {

    private OrderWorkerFactory() {
    }

    /** 在 worker 上注册订单链 + 采购单链的 workflow 与 activity（不启动 factory，便于测试自有环境接管）。 */
    public static void register(Worker worker, OrderActivities orderActivities,
                                PurchaseActivities purchaseActivities) {
        Objects.requireNonNull(worker, "worker 必填");
        Objects.requireNonNull(orderActivities, "OrderActivities 必填");
        Objects.requireNonNull(purchaseActivities, "PurchaseActivities 必填");
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class, PurchaseWorkflowImpl.class);
        worker.registerActivitiesImplementations(orderActivities, purchaseActivities);
    }

    /**
     * 生产装配并启动订单链 + 采购单链 worker（specs/0007 §7.2）。
     *
     * <p>EventPublisher 取 {@link NoopEventPublisher}（v1 事件总线未接入，specs/0007 §12）。
     *
     * @param client      Temporal client（连接自托管 server，ADR-0002）
     * @param store       订单文档库
     * @param fulfillment 采购履约服务（拆单 / 下单 / 发货回传）
     * @param rmaSync     售后状态同步服务
     * @return 已启动的 WorkerFactory（调用方负责 shutdown 以优雅退出）
     */
    public static WorkerFactory start(WorkflowClient client, OrderStore store,
                                      PurchaseFulfillmentService fulfillment, RmaSyncService rmaSync) {
        Objects.requireNonNull(client, "WorkflowClient 必填");
        Objects.requireNonNull(store, "OrderStore 必填");
        Objects.requireNonNull(fulfillment, "PurchaseFulfillmentService 必填");
        Objects.requireNonNull(rmaSync, "RmaSyncService 必填");
        OrderActivities orderActivities = new OrderActivitiesImpl(
                store, fulfillment, rmaSync, new NoopEventPublisher(), OrderWorkflow.class.getSimpleName());
        PurchaseActivities purchaseActivities = new PurchaseActivitiesImpl(
                fulfillment, new NoopEventPublisher(), PurchaseWorkflow.class.getSimpleName());
        WorkerFactory factory = WorkerFactory.newInstance(client);
        register(factory.newWorker(OrderRuntime.TASK_QUEUE), orderActivities, purchaseActivities);
        factory.start();
        return factory;
    }
}
