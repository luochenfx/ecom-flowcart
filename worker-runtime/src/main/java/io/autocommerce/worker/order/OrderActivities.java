package io.autocommerce.worker.order;

import io.autocommerce.core.order.model.OrderRma;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/**
 * 订单链 activity 契约（半步一 activity：落库 + 外部副作用各归其位，ADR-0002）。
 *
 * <p>顺序（由 {@link OrderWorkflowImpl} 编排，确定性）：
 * <ol>
 *   <li>{@link #prepare} —— 读订单聚合、判定是否已付款、（付款时）广播 {@code order.paid}，并给出拆单
 *       结果（供应商集）；</li>
 *   <li>（订单链为每个供应商起一个 {@link PurchaseWorkflow} child workflow）：解密地址 → 下单 + 支付
 *       → 发货回传 —— 采购轴自有 workflow，销售侧 SHIPPED 由采购集合派生；</li>
 *   <li>{@link #syncRmas} —— 售后状态只读同步（终态 → 广播 {@code rma.closed}）；</li>
 *   <li>{@link #summarize} —— 回读收敛后的销售履约轴。</li>
 * </ol>
 *
 * <p>事件纪律：所有 Domain Event 一律在对应 activity <b>落库之后</b> 才广播（specs/0016 §0.3），
 * 本接口不提供任何"先发事件"的入口。
 */
@ActivityInterface
public interface OrderActivities {

    /** 读订单聚合，判定是否已付款并（付款时）广播 order.paid；给出拆单供应商集。 */
    @ActivityMethod
    OrderView prepare(OrderWorkflowInput input);

    /** 售后状态只读同步（sentinel，不写平台）。 */
    @ActivityMethod
    List<OrderRma> syncRmas(OrderWorkflowInput input);

    /** 回读收敛后的销售履约轴与采购单 id 集。 */
    @ActivityMethod
    OrderSummary summarize(OrderWorkflowInput input);
}
