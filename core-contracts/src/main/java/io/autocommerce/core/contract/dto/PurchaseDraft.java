package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.order.model.SupplierRef;

import java.util.List;

/**
 * 1688 采购草稿（PurchaseCapability.createPurchase 入参）。由 order workflow 组装
 * （销售订单行 → 同供应商采购行 + 明文收货地址），下采购单前地址已解密。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PurchaseDraft(
        SupplierRef supplierRef,
        List<PurchaseDraftItem> items,
        DecryptedAddress recipient) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PurchaseDraftItem(String sourceSkuId, Integer quantity, Money unitPrice) {
    }
}
