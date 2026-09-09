package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractObjectMapper;
import io.autocommerce.core.testutil.ContractSchemas;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testing Decisions §1 双向契约测试（message 域）。
 * <ul>
 *   <li>envelope/dead_letter 文档：golden → MessageDocument → 关键字段断言 → 序列化过根 schema 且等价；</li>
 *   <li>payload defs：首批 7 事件各以 payload record → JSON，按 type 命中 $defs 校验
 *       （repo 即 registry：envelope.type → payload def 的路由由 ContractSchemas.payloadFor 表达）。</li>
 * </ul>
 */
class MessageContractTest {

    private static final String GOLDEN = "/fixtures/message.json";

    private final ObjectMapper mapper = ContractObjectMapper.create();

    @Test
    void jsonToModel_keepsEnvelopeAndDeadLetter() throws Exception {
        MessageDocument doc = mapper.treeToValue(golden(), MessageDocument.class);

        assertThat(doc.schemaVersion()).isEqualTo("0.1.0");

        Envelope envelope = doc.envelope();
        assertThat(envelope.id()).isEqualTo("1f0c1f2e-8f7a-4b3c-9d2e-3a4b5c6d7e8f");
        assertThat(envelope.type()).isEqualTo(EventTypes.ORDER_PAID);
        assertThat(envelope.version()).isEqualTo("1.0");
        assertThat(envelope.producer()).isEqualTo("order-sync-worker");
        assertThat(envelope.entityRef().type()).isEqualTo("order");
        assertThat(envelope.entityRef().id()).isEqualTo("order-taobao-202609090001");
        assertThat(envelope.correlationId()).isEqualTo("order-taobao-202609090001");
        assertThat(envelope.traceId()).isEqualTo("trace-abc-123");
        // payload 轻量引用直通（JsonNode，数据真相在业务库）
        assertThat(envelope.payload().get("order_id").asText()).isEqualTo("order-taobao-202609090001");
        assertThat(envelope.payload().get("payment_amount").decimalValue()).isEqualByComparingTo("148.0");

        DeadLetter dead = doc.deadLetter().deadLetter();
        assertThat(dead.reason()).isEqualTo(DeadLetterReason.SCHEMA_VALIDATION_FAILED);
        assertThat(dead.retryCount()).isZero();
        assertThat(dead.queue()).isEqualTo("domain.order.paid");
        assertThat(dead.error().className()).contains("SchemaViolation");
        // 原 envelope 完整包裹（重放素材不丢 payload）
        assertThat(dead.originalMessage().type()).isEqualTo(EventTypes.LISTING_PUBLISHED);
        assertThat(dead.originalMessage().payload().get("platform_item_id").asText())
                .isEqualTo("tb-item-6688990011");
    }

    @Test
    void modelToJson_passesSchema_andRoundTripsExactly() throws Exception {
        MessageDocument doc = mapper.treeToValue(golden(), MessageDocument.class);

        JsonNode json = mapper.valueToTree(doc);

        ContractAssertions.assertValid(ContractSchemas.message(), json, "MessageDocument");
        ContractAssertions.assertSemanticallyEquals(golden(), json, "MessageDocument");
    }

    @Test
    void eachEventType_mapsToPayloadDef_andSerializationPassesDefSchema() throws Exception {
        Map<String, Object> samples = payloadSamples();
        assertThat(samples.keySet())
                .containsExactlyInAnyOrderElementsOf(ContractSchemas.EVENT_TYPE_TO_PAYLOAD_DEF.keySet());

        for (Map.Entry<String, Object> e : samples.entrySet()) {
            String type = e.getKey();
            JsonNode payloadJson = mapper.valueToTree(e.getValue());
            ContractAssertions.assertValid(ContractSchemas.payloadFor(type), payloadJson,
                    type + " payload 应过 $defs/" + ContractSchemas.EVENT_TYPE_TO_PAYLOAD_DEF.get(type));
        }
    }

    private static Map<String, Object> payloadSamples() {
        return Map.of(
                EventTypes.ORDER_PAID,
                new OrderPaidPayload("order-taobao-202609090001", new BigDecimal("148.00"), "CNY",
                        "2026-09-09T10:05:00Z"),
                EventTypes.LISTING_PUBLISHED,
                new ListingPublishedPayload("listing-4001", "tb-item-6688990011", "2026-09-09T12:00:00Z"),
                EventTypes.LISTING_AMBIGUOUS,
                new ListingAmbiguousPayload("listing-4001", "timeout:no-confirmation"),
                EventTypes.PURCHASE_SHIPPED,
                new PurchaseShippedPayload("purchase-9001", "2026-09-09T16:00:00Z"),
                EventTypes.RMA_CLOSED,
                new RmaClosedPayload("rma-7001", "order-taobao-202609090001", RmaOutcome.REFUNDED),
                EventTypes.SYS_WORKFLOW_FAILED,
                new SysWorkflowFailedPayload("content-list", "content-listing-4001", "run-1",
                        WorkflowErrorType.RETRYABLE_EXHAUSTED, "media.process retries exhausted"),
                EventTypes.SYS_MESSAGE_DEAD_LETTERED,
                new SysMessageDeadLetteredPayload("domain.order.paid", "1f0c1f2e-8f7a-4b3c-9d2e-3a4b5c6d7e8f",
                        "SCHEMA_VALIDATION_FAILED", "2026-09-09T12:01:00Z"));
    }

    private JsonNode golden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(GOLDEN)) {
            assertThat(in).as("golden fixture 缺失: %s", GOLDEN).isNotNull();
            return mapper.readTree(in);
        }
    }
}
