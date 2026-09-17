package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.catalog.model.Provenance;

import java.util.List;

/**
 * PurchaseOrder（采购单）—— schema: PurchaseOrder。我方在货源平台（1688）对某供应商下达的
 * 采购请求；一销售 Order → 1:N PurchaseOrder（跨供应商必须拆单）。purchaseStatus 为采购
 * canonical 状态轴（独立于销售履约轴）；platformStatus 为 1688 侧原文旁路。
 *
 * <p>platformRaw 为 1688 采购响应原文逃生口（JSONB 直通，可空）：标准模型未覆盖的字段
 * （金额粒度 / 运费 / 支付有效期等）在此直通落库，不逐字段建模、不造映射 DSL
 * （specs/0005 §3 转换三分）。
 *
 * @param platformRaw 1688 采购响应原文（逃生口，可空；未覆盖字段直通，审计/对账兜底）
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PurchaseOrder(
        String purchaseOrderId,
        String orderId,
        SupplierRef supplierRef,
        String platformPurchaseNo,
        PurchaseStatus purchaseStatus,
        String platformStatus,
        JsonNode platformRaw,
        List<PurchaseLine> lines,
        Money amount,
        List<Tracking> tracking,
        Timestamps timestamps,
        Provenance provenance) {
}
