package io.autocommerce.order.status;

import io.autocommerce.core.message.RmaOutcome;
import io.autocommerce.core.order.model.FulfillmentStatus;
import io.autocommerce.core.order.model.Money;
import io.autocommerce.core.order.model.OrderRma;
import io.autocommerce.core.order.model.PurchaseOrder;
import io.autocommerce.core.order.model.PurchaseStatus;
import io.autocommerce.core.order.model.RmaStatus;
import io.autocommerce.core.order.model.RmaType;
import io.autocommerce.order.testsupport.OrderFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link FulfillmentDeriver}：双轴状态机的纯派生规则（穷举边界）。 */
class FulfillmentDeriverTest {

    @Test
    void paidDetection() {
        assertThat(FulfillmentDeriver.isPaid(FulfillmentStatus.AWAITING_PURCHASE)).isTrue();
        assertThat(FulfillmentDeriver.isPaid(FulfillmentStatus.SHIPPED)).isTrue();
        assertThat(FulfillmentDeriver.isPaid(FulfillmentStatus.PENDING_PAYMENT)).isFalse();
        assertThat(FulfillmentDeriver.isPaid(FulfillmentStatus.CANCELLED)).isFalse();
        assertThat(FulfillmentDeriver.isPaid(null)).isFalse();
    }

    @Test
    void salesAxisDerivedFromPurchaseSet() {
        assertThat(FulfillmentDeriver.deriveSales(List.of(), List.of()))
                .isEqualTo(FulfillmentStatus.AWAITING_PURCHASE);
        assertThat(FulfillmentDeriver.deriveSales(List.of(po(PurchaseStatus.PAID)), List.of()))
                .isEqualTo(FulfillmentStatus.PURCHASING);
        assertThat(FulfillmentDeriver.deriveSales(
                List.of(po(PurchaseStatus.SHIPPED), po(PurchaseStatus.PAID)), List.of()))
                .isEqualTo(FulfillmentStatus.PARTIALLY_SHIPPED);
        assertThat(FulfillmentDeriver.deriveSales(
                List.of(po(PurchaseStatus.SHIPPED), po(PurchaseStatus.COMPLETED)), List.of()))
                .isEqualTo(FulfillmentStatus.SHIPPED);
    }

    @Test
    void rmaOverlayDerivesRefundingOrDisputedOnlyWhileOpen() {
        List<PurchaseOrder> allShipped = List.of(po(PurchaseStatus.SHIPPED), po(PurchaseStatus.SHIPPED));

        // 未收敛 RMA → 销售侧叠加派生（采购已全部发货 base=SHIPPED，被 RMA overlay 覆盖）
        assertThat(FulfillmentDeriver.deriveSales(allShipped,
                List.of(rma(RmaType.REFUND, RmaStatus.WAITING_SELLER))))
                .isEqualTo(FulfillmentStatus.REFUNDING);
        assertThat(FulfillmentDeriver.deriveSales(allShipped,
                List.of(rma(RmaType.DISPUTE, RmaStatus.OPEN))))
                .isEqualTo(FulfillmentStatus.DISPUTED);
        // 已收敛（终态）→ 回到 base（采购聚合态）
        assertThat(FulfillmentDeriver.deriveSales(allShipped,
                List.of(rma(RmaType.REFUND, RmaStatus.REFUNDED))))
                .isEqualTo(FulfillmentStatus.SHIPPED);
    }

    @Test
    void rmaOutcomeMapping() {
        assertThat(FulfillmentDeriver.outcomeFor(RmaStatus.REFUNDED)).isEqualTo(RmaOutcome.REFUNDED);
        assertThat(FulfillmentDeriver.outcomeFor(RmaStatus.PARTIAL_REFUNDED))
                .isEqualTo(RmaOutcome.PARTIAL_REFUNDED);
        assertThat(FulfillmentDeriver.outcomeFor(RmaStatus.CLOSED))
                .isEqualTo(RmaOutcome.CLOSED_NO_REFUND);
        assertThat(FulfillmentDeriver.outcomeFor(RmaStatus.ESCALATED)).isEqualTo(RmaOutcome.ESCALATED);
        assertThat(FulfillmentDeriver.outcomeFor(RmaStatus.WAITING_SELLER)).isNull();
    }

    private static PurchaseOrder po(PurchaseStatus status) {
        return new PurchaseOrder("purchase-x", OrderFixtures.ORDER_ID, null, "1688PO", status,
                null, null, List.of(), null, List.of(), null, null);
    }

    private static OrderRma rma(RmaType type, RmaStatus status) {
        return new OrderRma("rma-x", OrderFixtures.ORDER_ID, type, "FX-refund", status, null,
                List.of(), new Money(new BigDecimal("79.00"), "CNY"), null, null, null);
    }
}
