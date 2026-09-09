package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 行级业务事实快照（schema: LineSnapshot，不可变）。
 * myRef 可空（若可回溯到自有商品）；titleText 可空（下单时标题原文，非必填）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LineSnapshot(
        String snapshotLineId,
        ExternalItemRef externalItemRef,
        String myRef,
        String titleText,
        String specText,
        Money unitPrice,
        Integer quantity,
        Money lineAmount,
        String imageUrl) {
}
