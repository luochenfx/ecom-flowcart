package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.catalog.model.Provenance;

import java.util.List;

/**
 * OrderRMA（售后单）—— schema: OrderRma。统一售后子实体：国内退款（REFUND）与跨境纠纷
 * （DISPUTE）收敛为单一实体、两种入口。独立生命周期与 workflow；v1 动作留平台后台人工。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderRma(
        String rmaId,
        String orderId,
        RmaType type,
        String platformRmaId,
        RmaStatus rmaStatus,
        String platformStatus,
        List<RmaLineRef> lines,
        Money amount,
        String reason,
        Timestamps timestamps,
        Provenance provenance) {
}
