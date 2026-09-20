package io.autocommerce.worker.order;

import io.autocommerce.core.order.model.OrderRma;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单链 workflow 实现（确定性编排壳）：顺序调度"每步一个 activity"，自身不做任何 I/O / 时钟 /
 * 随机数——所有副作用在 activity 侧（{@link OrderActivitiesImpl}），这是 Temporal 重放的前提。
 *
 * <p>顺序即业务链：就绪（+order.paid）→ <b>为每个供应商起一个采购单 child workflow</b>（解密地址 +
 * 下单/支付 + 发货回传，+purchase.shipped）→ RMA 读（+rma.closed）→ 摘要。Temporal Event History 即成为
 * "这条链跑过什么"的真相（ADR-0002）。
 *
 * <p><b>Order → 1:N 拆单</b>：拆单结果（供应商集）来自 {@link OrderActivities#prepare} 的 activity 结果
 * （确定性入 history），逐个起 {@link PurchaseWorkflow} child——采购单自有状态轴 / workflow，销售侧
 * SHIPPED 由采购 workflow 结果集合<b>派生</b>（无双写，specs/0003 §4）。
 *
 * <p>硬依赖失败不经本类处理：activity / child workflow 抛出的异常由 Temporal 按其 RetryOptions 判定，
 * 耗尽后本 workflow 直接 failed——不吞异常、不降级。
 */
public final class OrderWorkflowImpl implements OrderWorkflow {

    private final OrderActivities activities =
            Workflow.newActivityStub(OrderActivities.class, OrderActivityOptions.defaults());

    @Override
    public OrderWorkflowResult run(OrderWorkflowInput input) {
        OrderView view = activities.prepare(input);
        List<String> purchaseOrderIds = new ArrayList<>();
        if (view.paid()) {
            for (String supplierId : view.supplierIds()) {
                purchaseOrderIds.add(startPurchase(input, supplierId).purchaseOrderId());
            }
        }
        List<OrderRma> rmas = activities.syncRmas(input);
        OrderSummary summary = activities.summarize(input);
        return new OrderWorkflowResult(input.orderId(), input.platformOrderNo(),
                summary.fulfillmentStatus(),
                List.copyOf(purchaseOrderIds),
                rmas.stream().map(OrderRma::rmaId).toList());
    }

    /** 为该供应商起采购单 child workflow（workflowId = purchase-{orderId}-{supplierId}，确定性）。 */
    private PurchaseWorkflowResult startPurchase(OrderWorkflowInput input, String supplierId) {
        ChildWorkflowOptions options = ChildWorkflowOptions.newBuilder()
                .setWorkflowId(PurchaseRuntime.workflowIdFor(input.orderId(), supplierId))
                .setWorkflowIdReusePolicy(
                        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .build();
        PurchaseWorkflow child = Workflow.newChildWorkflowStub(PurchaseWorkflow.class, options);
        return child.run(new PurchaseWorkflowInput(input.orderId(), supplierId));
    }
}
