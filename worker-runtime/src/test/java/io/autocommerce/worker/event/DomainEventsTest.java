package io.autocommerce.worker.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.autocommerce.core.message.Envelope;
import io.autocommerce.core.message.RmaOutcome;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractObjectMapper;
import io.autocommerce.core.testutil.ContractSchemas;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DomainEvents} 幂等锚纪律：{@code envelope.id} 由事实键（type + entity_ref + occurred_at）
 * 确定性派生——同一业务事实重放产出<b>同一</b> id（activity 失败重跑 / broker 重投皆可去重），
 * 不同事实产出不同 id；且 id 保持 {@code message.schema.json} 的 {@code Envelope.id}
 * （{@code format: uuid}）契约形态。
 */
class DomainEventsTest {

    private static final ObjectMapper MAPPER = ContractObjectMapper.create();

    @Test
    void sameFactReplayYieldsSameId() {
        Envelope orderPaidFirst = DomainEvents.orderPaid("OrderWorkflow", "order-x",
                new BigDecimal("148.00"), "CNY", "2026-09-09T10:05:30Z");
        Envelope orderPaidReplay = DomainEvents.orderPaid("OrderWorkflow", "order-x",
                new BigDecimal("148.00"), "CNY", "2026-09-09T10:05:30Z");
        assertThat(orderPaidReplay.id()).as("order.paid 同一事实重放 → 同一幂等锚")
                .isEqualTo(orderPaidFirst.id());

        Envelope shippedFirst = DomainEvents.purchaseShipped("PurchaseWorkflow", "order-x",
                "purchase-order-x-s1", "2026-09-09T16:00:00Z");
        Envelope shippedReplay = DomainEvents.purchaseShipped("PurchaseWorkflow", "order-x",
                "purchase-order-x-s1", "2026-09-09T16:00:00Z");
        assertThat(shippedReplay.id()).as("purchase.shipped 同一事实重放 → 同一幂等锚")
                .isEqualTo(shippedFirst.id());

        Envelope closedFirst = DomainEvents.rmaClosed("OrderWorkflow", "order-x", "rma-x",
                RmaOutcome.REFUNDED, "2026-09-09T14:00:00Z");
        Envelope closedReplay = DomainEvents.rmaClosed("OrderWorkflow", "order-x", "rma-x",
                RmaOutcome.REFUNDED, "2026-09-09T14:00:00Z");
        assertThat(closedReplay.id()).as("rma.closed 同一事实重放 → 同一幂等锚")
                .isEqualTo(closedFirst.id());
    }

    @Test
    void differentFactYieldsDifferentId() {
        Envelope firstSupplier = DomainEvents.purchaseShipped("PurchaseWorkflow", "order-x",
                "purchase-order-x-s1", "2026-09-09T16:00:00Z");
        Envelope secondSupplier = DomainEvents.purchaseShipped("PurchaseWorkflow", "order-x",
                "purchase-order-x-s2", "2026-09-09T16:00:00Z");
        assertThat(secondSupplier.id()).as("同 type 不同 entity_ref → 不同 id")
                .isNotEqualTo(firstSupplier.id());

        Envelope orderPaid = DomainEvents.orderPaid("OrderWorkflow", "order-x",
                new BigDecimal("148.00"), "CNY", "2026-09-09T10:05:30Z");
        assertThat(firstSupplier.id()).as("不同 type → 不同 id").isNotEqualTo(orderPaid.id());
    }

    @Test
    void envelopeIdKeepsUuidContractShape() {
        Envelope envelope = DomainEvents.rmaClosed("OrderWorkflow", "order-x", "rma-x",
                RmaOutcome.REFUNDED, "2026-09-09T14:00:00Z");

        assertThat(envelope.id())
                .as("Envelope.id 契约 format: uuid —— 确定性派生仍须保持 RFC-4122 uuid 形态")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(envelope.occurredAt()).as("occurred_at = 已落库事实时间").isEqualTo("2026-09-09T14:00:00Z");

        ObjectNode document = MAPPER.createObjectNode();
        document.put("schema_version", "0.1.0");
        document.set("envelope", MAPPER.valueToTree(envelope));
        ContractAssertions.assertValid(ContractSchemas.message(), document,
                "Domain Event envelope 过 message.schema.json 契约门");
    }
}
