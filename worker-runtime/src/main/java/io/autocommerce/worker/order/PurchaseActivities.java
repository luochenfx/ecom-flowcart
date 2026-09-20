package io.autocommerce.worker.order;

import io.autocommerce.core.order.model.PurchaseOrder;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 采购单链 activity 契约（半步一 activity：落库 + 外部副作用各归其位，ADR-0002）。
 *
 * <p>顺序（由 {@link PurchaseWorkflowImpl} 编排，确定性）：
 * <ol>
 *   <li>{@link #ensureAddressDecrypted} —— 下采购单前把收货地址 MASKED → DECRYPTED（仅确有采购计划时）；</li>
 *   <li>{@link #placePurchase} —— 向该供应商下单 + 支付（幂等：已有采购单直接返回）；</li>
 *   <li>{@link #returnShipment} —— 供应商物流 → 回传销售平台；落库后广播 {@code purchase.shipped}。</li>
 * </ol>
 *
 * <p>事件纪律：Domain Event 一律在对应用户动作 <b>落库之后</b> 才广播（specs/0016 §0.3），本接口不提供
 * 任何"先发事件"的入口。
 */
@ActivityInterface
public interface PurchaseActivities {

    /** 下采购单前确保地址已解密（幂等：已 DECRYPTED 复用本地密文，不重复调平台解密 API）。 */
    @ActivityMethod
    void ensureAddressDecrypted(PurchaseWorkflowInput input);

    /** 向该供应商下单 + 支付（幂等：已有采购单直接返回，不重复下单）。 */
    @ActivityMethod
    PurchaseOrder placePurchase(PurchaseWorkflowInput input);

    /** 供应商发货 → 回传销售平台，推进采购轴（落库后广播 purchase.shipped，幂等：已终态直接返回）。 */
    @ActivityMethod
    PurchaseOrder returnShipment(PurchaseWorkflowInput input);
}
