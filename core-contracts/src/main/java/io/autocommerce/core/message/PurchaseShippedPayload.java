package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * purchase.shipped 事件负载（schema: PurchaseShippedPayload）。采购单供应商已发货
 * （tracking 细节回读 PurchaseOrder，此处仅信号）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PurchaseShippedPayload(String purchaseOrderId, String shippedAt) {
}
