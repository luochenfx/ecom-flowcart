package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;

/**
 * 订单模型文档（schema: order.schema.json 根对象）。schemaVersion 固定 "0.1.0"。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderModel(
        String schemaVersion,
        List<Order> orders,
        List<OrderLine> orderLines,
        List<OrderSnapshot> orderSnapshots,
        List<PurchaseOrder> purchaseOrders,
        List<OrderRma> rmas,
        List<ChannelSyncState> channelSyncStates) {
}
