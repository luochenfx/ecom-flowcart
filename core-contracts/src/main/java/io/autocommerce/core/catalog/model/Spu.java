package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;
import java.util.Map;

/**
 * SPU（标准产品单元，master）—— schema: Spu。
 * 平台无关 master：canonical 标题/描述/图片/规格/成本价；titles/images/skus/provenance 必填。
 * descriptions/sourceCategories/attributes 可选。titles/descriptions 为 Map&lt;locale,string&gt;
 * （canonical i18n，至少含来源 locale）。
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
        Provenance provenance) {
}
