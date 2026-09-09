package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 1688 采购结果（PurchaseCapability.createPurchase 返回）。platformPurchaseNo 为 flow=saleproxy
 * 下单返回的采购单号（PurchaseOrder.platform_purchase_no）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PurchaseResult(String platformPurchaseNo) {
}
