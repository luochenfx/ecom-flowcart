package io.autocommerce.worker.order;

import io.autocommerce.core.order.model.PurchaseOrder;
import io.temporal.workflow.Workflow;

/**
 * 采购单链 workflow 实现（确定性编排壳）：顺序调度「下采购单（先解密地址）→ 发货回传」，自身不做任何
 * I/O / 时钟 / 随机数——所有副作用在 activity 侧（{@link PurchaseActivitiesImpl}），这是 Temporal 重放的
 * 前提（与 {@link OrderWorkflowImpl} / {@code ContentWorkflowImpl} 同例）。
 *
 * <p>顺序即采购轴业务链：确保地址已解密（仅下单前一次）→ 下单 + 支付 → 供应商发货 → 回传销售平台。
 * Temporal Event History 即成为"这张采购单跑过什么"的真相（ADR-0002）。
 *
 * <p>硬依赖失败不经本类处理：activity 抛出的异常由 Temporal 按 {@link PurchaseActivityOptions} 的
 * RetryOptions 判定，耗尽后本 workflow 直接 failed——不吞异常、不降级。
 */
public final class PurchaseWorkflowImpl implements PurchaseWorkflow {

    private final PurchaseActivities activities =
            Workflow.newActivityStub(PurchaseActivities.class, PurchaseActivityOptions.defaults());

    @Override
    public PurchaseWorkflowResult run(PurchaseWorkflowInput input) {
        activities.ensureAddressDecrypted(input);
        PurchaseOrder placed = activities.placePurchase(input);
        PurchaseOrder shipped = activities.returnShipment(input);
        return new PurchaseWorkflowResult(input.orderId(), input.supplierId(),
                placed.purchaseOrderId(), shipped.purchaseStatus());
    }
}
