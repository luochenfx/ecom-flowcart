package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 采购行（schema: PurchaseLine）。sourceSkuRef = 1688 货源 SKU（skuMap skuId）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PurchaseLine(
        String purchaseLineId,
        String orderLineRef,
        String sourceSkuRef,
        Integer quantity,
        Money unitPrice) {
}
