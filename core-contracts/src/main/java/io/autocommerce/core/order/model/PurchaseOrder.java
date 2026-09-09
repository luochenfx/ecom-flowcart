package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.catalog.model.Provenance;

import java.util.List;

/**
 * PurchaseOrder（采购单）—— schema: PurchaseOrder。我方在货源平台（1688）对某供应商下达的
 * 采购请求；一销售 Order → 1:N PurchaseOrder（跨供应商必须拆单）。purchaseStatus 为采购
 * canonical 状态轴（独立于销售履约轴）；platformStatus 为 1688 侧原文旁路。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PurchaseOrder(
        String purchaseOrderId,
        String orderId,
        SupplierRef supplierRef,
        String platformPurchaseNo,
        PurchaseStatus purchaseStatus,
        String platformStatus,
        List<PurchaseLine> lines,
        Money amount,
        List<Tracking> tracking,
        Timestamps timestamps,
        Provenance provenance) {
}
