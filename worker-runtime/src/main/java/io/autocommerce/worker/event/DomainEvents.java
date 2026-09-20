package io.autocommerce.worker.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.message.EntityRef;
import io.autocommerce.core.message.Envelope;
import io.autocommerce.core.message.EventTypes;
import io.autocommerce.core.message.OrderPaidPayload;
import io.autocommerce.core.message.PurchaseShippedPayload;
import io.autocommerce.core.message.RmaClosedPayload;
import io.autocommerce.core.message.RmaOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain Event envelope 工厂（specs/0016 §0.3 / ADR-0006：总线只承载已落库事实的广播）。
 *
 * <p>每条事件 = {@link Envelope}（transport-agnostic 统一信封）：{@code id}（uuid，消费端幂等锚）、
 * {@code type}（{@link EventTypes} 常量）、{@code version}（payload 语义化版本，v1 = "1.0"）、
 * {@code occurred_at}、{@code producer}（workflow type）、{@code entity_ref}（回读业务库用）、
 * {@code correlation_id}（业务链根 id，此处 = orderId）、{@code payload}（轻量实体引用 + 摘要）。
 *
 * <p>payload 与 {@code schemas/message.schema.json} 的 {@code $defs} 同名 record 一一对应，可经
 * {@code ContractSchemas.payloadFor(type)} 做运行时校验（测试里即契约门）。
 */
public final class DomainEvents {

    /** payload schema 语义化版本（message.schema.json 事件目录，v1 全部 1.0）。 */
    public static final String VERSION = "1.0";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private DomainEvents() {
    }

    /** {@code order.paid}：销售订单买家已付款（业务事实已落库后广播）。 */
    public static Envelope orderPaid(String producer, String orderId, BigDecimal paymentAmount,
                                     String currency, String paidAt) {
        return envelope(EventTypes.ORDER_PAID, producer, new EntityRef("order", orderId), orderId,
                new OrderPaidPayload(orderId, paymentAmount, currency, paidAt));
    }

    /** {@code purchase.shipped}：采购单供应商已发货（tracking 细节回读 PurchaseOrder）。 */
    public static Envelope purchaseShipped(String producer, String orderId, String purchaseOrderId,
                                           String shippedAt) {
        return envelope(EventTypes.PURCHASE_SHIPPED, producer,
                new EntityRef("purchase_order", purchaseOrderId), orderId,
                new PurchaseShippedPayload(purchaseOrderId, shippedAt));
    }

    /** {@code rma.closed}：售后单完结（outcome 摘要，明细回读 OrderRMA）。 */
    public static Envelope rmaClosed(String producer, String orderId, String rmaId, RmaOutcome outcome) {
        return envelope(EventTypes.RMA_CLOSED, producer, new EntityRef("rma", rmaId), orderId,
                new RmaClosedPayload(rmaId, orderId, outcome));
    }

    private static Envelope envelope(String type, String producer, EntityRef entityRef,
                                     String correlationId, Object payload) {
        return new Envelope(UUID.randomUUID().toString(), type, VERSION, Instant.now().toString(),
                producer, entityRef, correlationId, null, MAPPER.valueToTree(payload));
    }
}
