package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.order.model.Tracking;

import java.util.List;

/**
 * 1688 物流轨迹（PurchaseCapability.fetchLogistics 返回）。tracking 复用标准 Tracking
 * （company/trackingNo/status/url；logistics.trace 取回）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LogisticsTrace(String platformPurchaseNo, List<Tracking> tracking) {
}
