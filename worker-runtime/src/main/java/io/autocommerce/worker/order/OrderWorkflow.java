package io.autocommerce.worker.order;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 订单链 workflow（#22 AC-1）：每销售订单一个 {@code order-{platform}-{orderNo}} 执行。
 *
 * <p>workflowId 确定性 = {@link OrderRuntime#workflowIdFor(String, String)}（specs/0003 §6 幂等第一级）：
 * 重复触发（webhook 重推 / 游标重读 / 手工重放）→ 复用既有 execution 而非并发跑第二遍
 * （采购 / 发货回传是花钱的外部副作用，不能重复执行）。
 */
@WorkflowInterface
public interface OrderWorkflow {

    /** 跑完"下单 → 拆采购单 → 发货回传 → RMA 读"并收敛；硬依赖失败则本方法失败（不返回结果）。 */
    @WorkflowMethod
    OrderWorkflowResult run(OrderWorkflowInput input);
}
