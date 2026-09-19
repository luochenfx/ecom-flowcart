package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 1688 采购结果（PurchaseCapability.createPurchase 返回）。platformPurchaseNo 为 flow=saleproxy
 * 下单返回的采购单号（PurchaseOrder.platform_purchase_no）。
 *
 * <p>platformRaw 为 fastCreateOrder 响应原文逃生口（JsonNode 直通，可空）：标准模型未覆盖的字段
 * （订单金额 / 运费 / 支付有效期等）在此直通，由 domain 侧落 {@code PurchaseOrder.platform_raw}
 * JSONB，不逐字段建模、不造映射 DSL（specs/0005 §3 转换三分、§10.1 形态决议：<b>单节点</b>，
 * 与 {@code OfferData.raw} 同形，<b>不</b>按端点分组）。
 *
 * <p>可空语义与 {@code OfferData.raw} 一致，<b>不加</b> {@code @Nullable}——core-contracts 零
 * Spring 层（{@code ArchitectureRulesTest} 断言 core 不得依赖 {@code org.springframework..}），
 * 故可空语义只写在本 {@code @param} javadoc。
 *
 * @param platformPurchaseNo 1688 采购单号（flow=saleproxy 下单返回）
 * @param platformRaw        fastCreateOrder 响应原文（逃生口，可空；未覆盖字段直通，审计/对账兜底）
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PurchaseResult(String platformPurchaseNo, JsonNode platformRaw) {
}
