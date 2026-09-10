package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.catalog.model.Attribute;
import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.catalog.model.SpecValue;

import java.util.List;

/**
 * 1688 offer 采集结果（{@code OfferFetchCapability.fetchOffer} 返回）——平台无关的"采集面"，
 * catalog slice 以此组装 SPU/SKU/MediaAsset master。
 *
 * <p>结构随 catalog slice（Slice 3）按 1688 真实 offer 形状细化（#17 遗留开放输入）：
 * 标题/图/规格价满足最小入库；来源类目（source_categories）与来源属性（attributes）按 offer
 * 响应可选解析——1688 {@code alibaba.product.get} 的 categoryId/categoryName 与 attributes[]
 * 字段名及规格组合串解析规则待 #23 真实 API 实测校准，本子集防御性读取、缺失即空列表。
 *
 * <p>未覆盖字段不逐字段建模：整体走 {@code raw} 逃生口（JsonNode，落 SPU platform_raw JSONB，
 * 审计/对账直通不丢），不造映射 DSL（specs/0005 §3 转换三分）。
 *
 * @param sourceCategories 来源平台类目（1688 categoryId/categoryName 可选解析）
 * @param attributes       来源属性快照（1688 attributes[] 可选解析，原样键名）
 * @param raw              offer 原始响应的 offer 子树（逃生口，标准模型未覆盖字段直通）
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OfferData(
        String externalId,
        String title,
        List<String> imageUrls,
        List<OfferSku> skus,
        List<CategoryRef> sourceCategories,
        List<Attribute> attributes,
        JsonNode raw) {

    /**
     * offer 内 SKU 规格价最小表达。
     *
     * <p><b>sourceSpecId 当前可为 null（本 issue #35 只补契约，采集侧填充属 #23）</b>：1688 下单键
     * {@code specId} 现在仍只落在 {@link OfferData#raw()} 逃生口里。本仓 fixture
     * （{@code adapter-1688/src/test/resources/fixtures/1688-offer-response.json}）是<b>自造样本</b>：
     * 其中两条不同 SKU 的 {@code specId} 取值相同（实为 {@code skuInfo.specs[].specId}，即规格维度
     * "颜色"的 id），<b>只能证明存在该字段、不能证明它是逐 SKU 的下单键</b>。真实语义由 #23 用真实响应
     * 校准——若逐 SKU 下单键另有其字段，本字段名不变、来源换成实际字段即可。
     *
     * @param sourceSkuId  1688 skuMap 原始 skuId（规格组合的内部 ID）
     * @param sourceSpecId 1688 skuMap 条目的 specId：下单请求（{@code alibaba.trade.fastCreateOrder} 的
     *                     {@code cargoParamList[]} 每项必填）所需的规格下单标识
     * @param specText     1688 skuMap 规格组合键原文（"颜色:红色;尺码:L" 之类，格式随 offer 变化，
     *                     保留原文供审计；解析规则 #23 实测校准）
     * @param specs        由 specText 尽力解析出的 name/value 对（解析失败为空列表，不丢原文）
     * @param price        该 SKU 价格
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OfferSku(
            String sourceSkuId,
            String sourceSpecId,
            String specText,
            List<SpecValue> specs,
            Money price) {
    }
}
