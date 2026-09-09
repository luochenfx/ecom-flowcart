package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 采购行引用（schema: PurchaseLineRef）。一销售行可拆多采购行（跨供应商拆单）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PurchaseLineRef(String purchaseOrderId, String purchaseLineId) {
}
