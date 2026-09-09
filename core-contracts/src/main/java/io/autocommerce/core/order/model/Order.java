package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.catalog.model.Provenance;

import java.util.List;

/**
 * Order（销售订单）—— schema: Order。身份 + 关联 + 派生态，不承载可变业务明细
 * （明细在快照）。orderId 确定性（order-{platform}-{orderNo} workflowId 业务键）；
 * platformRaw 平台订单原文（JSONB）审计/对账。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Order(
        String orderId,
        String channelId,
        String platform,
        String platformOrderNo,
        String platformStatus,
        String platformStatusTime,
        JsonNode platformRaw,
        FulfillmentStatus fulfillmentStatus,
        String snapshotId,
        ShippingAddress shippingAddress,
        String buyer,
        List<LineRef> lines,
        List<RmaRef> rmas,
        Timestamps timestamps,
        Provenance provenance) {
}
