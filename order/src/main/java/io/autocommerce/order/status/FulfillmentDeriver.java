package io.autocommerce.order.status;

import io.autocommerce.core.message.RmaOutcome;
import io.autocommerce.core.order.model.FulfillmentStatus;
import io.autocommerce.core.order.model.OrderRma;
import io.autocommerce.core.order.model.PurchaseOrder;
import io.autocommerce.core.order.model.PurchaseStatus;
import io.autocommerce.core.order.model.RmaStatus;
import io.autocommerce.core.order.model.RmaType;

import java.util.List;
import java.util.Set;

/**
 * 双轴状态机的派生规则（canonical，specs/0003 §4；平台原文旁路不逐平台翻译）。
 *
 * <p>销售履约轴与采购轴互相独立：采购单自有 {@link PurchaseStatus} 轴（由采购 workflow 维护），
 * 销售侧 {@link FulfillmentStatus} 是其下采购单集合的<b>派生聚合</b>——"全部发货才 SHIPPED、
 * 部分发货 PARTIALLY_SHIPPED"，不反向写采购状态。销售侧 {@code REFUNDING / DISPUTED} 由
 * {@link OrderRma} 存在派生，不反向写 RMA。
 *
 * <p>本类只做纯函数派生（无 I/O、无时钟），便于单测穷举边界。
 */
public final class FulfillmentDeriver {

    /** 已过支付点的履约态（含"待采购"及以后）——用于 order.paid 事件判定。 */
    private static final Set<FulfillmentStatus> PAID_OR_LATER = Set.of(
            FulfillmentStatus.AWAITING_PURCHASE,
            FulfillmentStatus.PURCHASING,
            FulfillmentStatus.PARTIALLY_SHIPPED,
            FulfillmentStatus.SHIPPED,
            FulfillmentStatus.COMPLETED,
            FulfillmentStatus.REFUNDING,
            FulfillmentStatus.DISPUTED);

    /** 采购侧"已发货"态（用于销售侧聚合）。 */
    private static final Set<PurchaseStatus> PURCHASE_SHIPPED = Set.of(
            PurchaseStatus.SHIPPED, PurchaseStatus.COMPLETED);

    /** RMA 未收敛态（"还没结束"——销售侧据此派生 REFUNDING / DISPUTED）。 */
    private static final Set<RmaStatus> RMA_OPEN = Set.of(
            RmaStatus.OPEN, RmaStatus.WAITING_SELLER, RmaStatus.WAITING_BUYER,
            RmaStatus.PARTIAL_REFUNDED, RmaStatus.ESCALATED);

    /** RMA 终态（据此发 rma.closed）。 */
    private static final Set<RmaStatus> RMA_TERMINAL = Set.of(
            RmaStatus.REFUNDED, RmaStatus.PARTIAL_REFUNDED, RmaStatus.CLOSED, RmaStatus.REJECTED);

    private FulfillmentDeriver() {
    }

    /** 销售订单是否已过支付点（order.paid 事件判定）。 */
    public static boolean isPaid(FulfillmentStatus status) {
        return status != null && PAID_OR_LATER.contains(status);
    }

    public static boolean isRmaTerminal(RmaStatus status) {
        return status != null && RMA_TERMINAL.contains(status);
    }

    public static boolean isRmaOpen(RmaStatus status) {
        return status != null && RMA_OPEN.contains(status);
    }

    /**
     * 销售履约轴派生的<b>唯一入口</b>（specs/0003 §4 / Spec #16 §0.3 派生纪律）：先由采购单集合聚合
     * 出 base，再叠加未收敛 RMA overlay。所有写销售轴的调用方（发货回传 / RMA 同步）一律经此处，
     * 杜绝同一 canonical 轴被两套不同规则写入。
     *
     * @param purchases 该订单当前采购单集合（聚合出"未采购 / 部分发货 / 全部发货"）
     * @param rmas      该订单当前 RMA 集合（存在未收敛 RMA 时叠加 REFUNDING / DISPUTED）
     */
    public static FulfillmentStatus deriveSales(List<PurchaseOrder> purchases, List<OrderRma> rmas) {
        return withRmas(salesFromPurchases(purchases), rmas);
    }

    /** 销售履约轴 ← 采购单集合派生（无采购 = AWAITING_PURCHASE；部分/全部发货）。{@link #deriveSales} 内部基元。 */
    private static FulfillmentStatus salesFromPurchases(List<PurchaseOrder> purchases) {
        if (purchases == null || purchases.isEmpty()) {
            return FulfillmentStatus.AWAITING_PURCHASE;
        }
        long shipped = purchases.stream()
                .filter(p -> p.purchaseStatus() != null && PURCHASE_SHIPPED.contains(p.purchaseStatus()))
                .count();
        if (shipped == 0) {
            return FulfillmentStatus.PURCHASING;
        }
        return shipped == purchases.size() ? FulfillmentStatus.SHIPPED : FulfillmentStatus.PARTIALLY_SHIPPED;
    }

    /**
     * 在既有履约轴上叠加 RMA 派生：存在未收敛 RMA 时，销售侧变为 REFUNDING（仅退款入口）或
     * DISPUTED（含纠纷入口）；RMA 全部收敛则回到 base（采购聚合态）。{@link #deriveSales} 内部基元。
     */
    private static FulfillmentStatus withRmas(FulfillmentStatus base, List<OrderRma> rmas) {
        List<OrderRma> open = (rmas == null ? List.<OrderRma>of() : rmas).stream()
                .filter(r -> isRmaOpen(r.rmaStatus()))
                .toList();
        if (open.isEmpty()) {
            return base;
        }
        boolean dispute = open.stream().anyMatch(r -> r.type() == RmaType.DISPUTE);
        return dispute ? FulfillmentStatus.DISPUTED : FulfillmentStatus.REFUNDING;
    }

    /** RMA 终态 → {@code rma.closed} 事件 outcome（未终结返回 null，不发事件）。 */
    public static RmaOutcome outcomeFor(RmaStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case REFUNDED -> RmaOutcome.REFUNDED;
            case PARTIAL_REFUNDED -> RmaOutcome.PARTIAL_REFUNDED;
            case CLOSED, REJECTED -> RmaOutcome.CLOSED_NO_REFUND;
            case ESCALATED -> RmaOutcome.ESCALATED;
            default -> null;
        };
    }
}
