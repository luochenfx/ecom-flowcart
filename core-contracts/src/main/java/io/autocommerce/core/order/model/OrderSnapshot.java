package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.catalog.model.Provenance;

import java.util.List;

/**
 * OrderSnapshot（建单快照，一等不可变）—— schema: OrderSnapshot。
 * 下单时固化的不可变业务事实：商品行 + 金额 + 地址掩码 + 平台原文。此后商品改价/SKU 变更
 * 不影响历史订单。不可 UPDATE 业务事实字段。platformRaw 可空（JsonNode 直通）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderSnapshot(
        String snapshotId,
        String orderId,
        String capturedAt,
        Amounts amounts,
        List<LineSnapshot> lineSnapshots,
        ShippingAddressMask shippingAddressMask,
        JsonNode platformRaw,
        Provenance provenance) {
}
