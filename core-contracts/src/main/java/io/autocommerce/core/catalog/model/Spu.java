package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;
import java.util.Map;

/**
 * SPU（标准产品单元，master）—— schema: Spu。
 * 平台无关 master：canonical 标题/描述/图片/规格/成本价；titles/images/skus/provenance 必填。
 * descriptions/sourceCategories/attributes 可选。titles/descriptions 为 Map&lt;locale,string&gt;
 * （canonical i18n，至少含来源 locale）。
 *
 * <p>{@code platformRaw} = 来源 offer 原始响应逃生口（schema 可选属性，JSONB；标准模型未覆盖的
 * 平台字段直通不丢，审计/对账）。由采集（OfferFetch → catalog）写入；后续加工不改写。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Spu(
        String spuId,
        SourceRef sourceRef,
        Map<String, String> titles,
        Map<String, String> descriptions,
        List<MediaRef> images,
        List<SkuRef> skus,
        List<CategoryRef> sourceCategories,
        List<Attribute> attributes,
        JsonNode platformRaw,
        Provenance provenance) {
}
