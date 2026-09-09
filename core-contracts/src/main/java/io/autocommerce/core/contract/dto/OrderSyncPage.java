package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.order.model.Order;
import io.autocommerce.core.order.model.OrderSnapshot;

import java.util.List;

/**
 * 订单同步一页（OrderSyncCapability.fetchOrders 返回）。
 * Adapter 做结构转换：平台订单 → 标准 Order + 建单 OrderSnapshot（业务事实固化）；
 * nextCursor 为空表示无更多页。OrderLine 等内部履约态由 order 模块落库时生成，不在此契约。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderSyncPage(List<Order> orders, List<OrderSnapshot> snapshots, SyncCursor nextCursor) {
}
