package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;
import java.util.Map;

/**
 * Listing（平台铺货实例）—— schema: Listing。铺货 workflow 的唯一输入。
 * 平台特化内容：改写稿/目标叶子类目/平台属性/规格映射/SKU 售价集（可只铺部分规格）。
 * listingId 为确定性 id（对 #11 workflowId 语义）；channelId = 目标渠道账号。
 *
 * <p>{@code degradedSteps} = 内容链 Step 级降级的留痕（specs/0006 §5/§6）：非硬依赖 Step
 * 失败后产物已按缺省值写入，此处登记供看板 HITL；空/null = 全 Step 正常。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Listing(
        String listingId,
        String spuId,
        String channelId,
        Map<String, String> titleOverrides,
        Map<String, String> descriptionOverrides,
        List<String> locales,
        CategoryRef platformCategory,
        List<PlatformAttribute> platformAttributes,
        List<SpecMapping> specMappings,
        List<ListingSku> skuSet,
        List<ListingImage> images,
        List<DegradedStep> degradedSteps,
        Provenance provenance) {
}
