package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.catalog.model.Money;

import java.util.List;

/**
 * 1688 offer 采集结果（OfferFetchCapability.fetchOffer 返回）。
 * 结构化最小面（标题/图/规格价）满足入库；未覆盖字段走 raw 逃生口（JSONB，审计/对账）。
 * 具体结构随 catalog slice（Slice 3）按 1688 真实 offer 形状细化。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OfferData(
        String externalId,
        String title,
        List<String> imageUrls,
        List<OfferSku> skus,
        JsonNode raw) {

    /** offer 内 SKU 规格价最小表达 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OfferSku(String sourceSkuId, String specText, Money price) {
    }
}
