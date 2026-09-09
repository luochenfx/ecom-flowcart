package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 发货回传通知（ShipmentCapability.notifyShipment 入参，specs/0001 发货回传链路）。
 * 供应商物流单号 → 销售平台。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ShipmentNotification(
        String platformOrderNo,
        String trackingCompany,
        String trackingNo,
        String trackingUrl) {
}
