package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.order.model.RmaStatus;

/**
 * 售后状态只读视图（RmaCapability.fetchRmaStatus 返回，v1 只读；操作留平台后台人工）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RmaStatusView(String platformRmaId, RmaStatus status, String platformStatus) {
}
