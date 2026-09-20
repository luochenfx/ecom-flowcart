package io.autocommerce.worker.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.message.EntityRef;
import io.autocommerce.core.message.Envelope;
import io.autocommerce.core.message.EventTypes;
import io.autocommerce.core.message.ListingAmbiguousPayload;
import io.autocommerce.core.message.ListingPublishedPayload;
import io.autocommerce.core.message.OrderPaidPayload;
import io.autocommerce.core.message.PurchaseShippedPayload;
import io.autocommerce.core.message.RmaClosedPayload;
import io.autocommerce.core.message.RmaOutcome;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Domain Event envelope 工厂（specs/0016 §0.3 / ADR-0006：总线只承载已落库事实的广播）。
 *
 * <p>每条事件 = {@link Envelope}（transport-agnostic 统一信封）：{@code id}（由事实键确定性派生的
 * 消费端幂等锚）、{@code type}（{@link EventTypes} 常量）、{@code version}（payload 语义化版本，
 * v1 = "1.0"）、{@code occurred_at}（<b>业务事实发生时间</b>，取自已落库事实、非广播时刻）、
 * {@code producer}（workflow type）、{@code entity_ref}（回读业务库用）、{@code correlation_id}
 * （业务链根 id，此处 = orderId）、{@code payload}（轻量实体引用 + 摘要）。
 *
 * <p><b>幂等锚确定性</b>：{@code id} = 由事实键
 * {@code type | entity_ref.type | entity_ref.id | occurred_at} 的 SHA-256 摘要确定性派生的
 * UUID（RFC 9562 <b>v8</b>「自定义算法」形态，非 SHA-1 系 v5 语义）。
 * 于是同一业务事实的重复广播（activity 失败重跑 / broker 重投）产出<b>同一</b> id，消费端据此去重；
 * 不同事实（含同 type 不同 {@code entity_ref}）产出不同 id。派生输入全部取自已落库事实，
 * <b>不含</b> {@code Instant.now()} / {@code System.currentTimeMillis()} 等非确定值。
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

    /**
     * {@code order.paid}：销售订单买家已付款（业务事实已落库后广播）。
     *
     * @param paidAt 已落库事实时间（{@code order.platformStatusTime} 或 {@code snapshot.capturedAt}）
     */
    public static Envelope orderPaid(String producer, String orderId, BigDecimal paymentAmount,
                                     String currency, String paidAt) {
        return envelope(EventTypes.ORDER_PAID, producer, new EntityRef("order", orderId), orderId, paidAt,
                new OrderPaidPayload(orderId, paymentAmount, currency, paidAt));
    }

    /**
     * {@code purchase.shipped}：采购单供应商已发货（tracking 细节回读 PurchaseOrder）。
     *
     * @param shippedAt 已落库采购单的事实时间（{@code PurchaseOrder.timestamps.updatedAt}），
     *                  <b>不可</b>传广播时刻——否则 activity 重跑会产出不同 id 的第二条 envelope
     */
    public static Envelope purchaseShipped(String producer, String orderId, String purchaseOrderId,
                                           String shippedAt) {
        return envelope(EventTypes.PURCHASE_SHIPPED, producer,
                new EntityRef("purchase_order", purchaseOrderId), orderId, shippedAt,
                new PurchaseShippedPayload(purchaseOrderId, shippedAt));
    }

    /**
     * {@code rma.closed}：售后单完结（outcome 摘要，明细回读 OrderRMA）。
     *
     * @param closedAt 已落库 RMA 的事实时间（{@code OrderRma.timestamps.updatedAt}），
     *                 <b>不可</b>传广播时刻（同上，保幂等锚稳定）
     */
    public static Envelope rmaClosed(String producer, String orderId, String rmaId, RmaOutcome outcome,
                                     String closedAt) {
        return envelope(EventTypes.RMA_CLOSED, producer, new EntityRef("rma", rmaId), orderId, closedAt,
                new RmaClosedPayload(rmaId, orderId, outcome));
    }

    /**
     * {@code listing.published}：铺货成功（PUBLISHED 终态广播，回填 platform_item_id）。
     *
     * @param publishedAt 已落库事实时间（{@code PublishState.publishedAt}），<b>不可</b>传广播时刻
     *                    ——否则 activity 重跑会产出不同 id 的第二条 envelope
     */
    public static Envelope listingPublished(String producer, String listingId, String platformItemId,
                                            String publishedAt) {
        return envelope(EventTypes.LISTING_PUBLISHED, producer, new EntityRef("listing", listingId), listingId,
                publishedAt, new ListingPublishedPayload(listingId, platformItemId, publishedAt));
    }

    /**
     * {@code listing.ambiguous}：铺货超时歧义挂起（等 reconcile / 人工，供看板 HITL）。
     *
     * @param occurredAt 已落库事实时间（{@code PublishState.updatedAt}，AMBIGUOUS 记录时刻），
     *                   <b>不可</b>传广播时刻（同上，保幂等锚稳定）
     */
    public static Envelope listingAmbiguous(String producer, String listingId, String reason,
                                            String occurredAt) {
        return envelope(EventTypes.LISTING_AMBIGUOUS, producer, new EntityRef("listing", listingId), listingId,
                occurredAt, new ListingAmbiguousPayload(listingId, reason));
    }

    private static Envelope envelope(String type, String producer, EntityRef entityRef,
                                     String correlationId, String occurredAt, Object payload) {
        return new Envelope(deterministicId(type, entityRef, occurredAt), type, VERSION, occurredAt,
                producer, entityRef, correlationId, null, MAPPER.valueToTree(payload));
    }

    /**
     * 幂等锚：由事实键 {@code type | entity_ref.type | entity_ref.id | occurred_at} 的 SHA-256 摘要
     * 确定性派生，并对齐 {@code message.schema.json} 的 {@code Envelope.id}（{@code format: uuid}）——
     * 取摘要前 16 字节按 RFC 9562 <b>v8</b>（自定义算法）编排为 UUID 字符串。输入全部取自已落库事实
     * → 同一事实重放必得同一 id；不同事实必得不同 id。
     */
    private static String deterministicId(String type, EntityRef entityRef, String occurredAt) {
        String factKey = type + '|' + entityRef.type() + '|' + entityRef.id() + '|' + occurredAt;
        byte[] hash = sha256(factKey);
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x80); // RFC 9562 version 8（自定义算法，非 SHA-1 v5）
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // RFC 4122 variant 10xx
        ByteBuffer buffer = ByteBuffer.wrap(hash);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用（JDK 必备算法）", e);
        }
    }
}
