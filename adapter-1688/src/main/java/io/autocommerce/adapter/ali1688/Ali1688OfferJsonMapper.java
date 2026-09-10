package io.autocommerce.adapter.ali1688;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.catalog.model.Attribute;
import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.catalog.model.SpecValue;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.OfferData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 1688 offer 结构转换（specs/0005 §3：结构转换 = Adapter 代码）。
 *
 * <p>输入 = {@code alibaba.product.get} 响应体（{"result": {...}, "success": bool,
 * "errorCode": "0", "errorMsg"} 包装形态；无包装时退化为 offer 子树直接可用），
 * 输出 = 平台无关采集面 {@link OfferData}（catalog slice 消费）。
 *
 * <p>子集覆盖（#19，字段名以 research/domestic-platforms.md 与阿里云社区文章为准）：
 * productId / subject / imageUrl / priceRanges / skuInfo.skuMap+specs / detailPage / status；
 * categoryId / categoryName / attributes[] 为防御性可选读取——真实 API 字段名待 #23 实测校准。
 * 其余字段一律不逐字段建模，随 {@code OfferData.raw}（= offer 子树）直通不丢。
 *
 * <p>失败语义：业务拒绝（success=false）抛 {@code AdapterException} NON_RETRYABLE
 * （errorCode/errorMsg 落 platformCode/message）；结构/解析失败同样 NON_RETRYABLE
 * （= bug 或格式不兼容，不是临时故障）。
 */
public final class Ali1688OfferJsonMapper {

    /** 默认金额币种：1688 批发报价为 CNY。 */
    static final String CURRENCY_CNY = "CNY";

    private final ObjectMapper objectMapper;

    public Ali1688OfferJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 将响应体（包装或裸 offer）转为采集面 OfferData。 */
    public OfferData map(String responseBody) {
        JsonNode body = parse(responseBody);
        if (isBusinessError(body)) {
            throw AdapterException.nonRetryable(
                    body.path("errorCode").asText("unknown"),
                    body.path("errorMsg").asText("1688 业务错误"));
        }
        JsonNode offer = unwrap(body);
        return mapOffer(offer);
    }

    private JsonNode parse(String responseBody) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (IOException e) {
            throw AdapterException.nonRetryable("bad-response", "响应体非合法 JSON: " + e.getMessage());
        }
    }

    private boolean isBusinessError(JsonNode body) {
        return body.isObject() && body.path("success").isBoolean() && !body.path("success").asBoolean();
    }

    /** 响应包装（success=true）取 result 子树；无包装（fixture/直连）用整棵 body。 */
    private JsonNode unwrap(JsonNode body) {
        JsonNode result = body.path("result");
        return result.isObject() ? result : body;
    }

    public OfferData mapOffer(JsonNode offer) {
        if (!offer.isObject()) {
            throw AdapterException.nonRetryable("bad-offer", "offer 响应非 JSON 对象");
        }
        String externalId = text(offer, "productId");
        if (externalId == null || externalId.isBlank()) {
            throw AdapterException.nonRetryable("missing-product-id", "offer 缺少 productId");
        }
        String title = text(offer, "subject");

        List<String> imageUrls = new ArrayList<>();
        String mainImage = text(offer, "imageUrl");
        if (mainImage != null && !mainImage.isBlank()) {
            imageUrls.add(mainImage);
        }

        List<OfferData.OfferSku> skus = mapSkus(offer.path("skuInfo"));
        List<CategoryRef> categories = mapCategories(offer);
        List<Attribute> attributes = mapAttributes(offer);

        // raw = offer 子树原样（逃生口）：标准模型未覆盖字段（priceRanges/detailPage/status/
        // skuInfo.specs 定义等）直通，catalog 落 Spu.platform_raw
        JsonNode raw = offer.deepCopy();
        return new OfferData(externalId, title, imageUrls, skus, categories, attributes, raw);
    }

    /**
     * skuInfo.skuMap → OfferSku 列表。skuMap key = 规格组合键原文（如 "颜色:黑色"），
     * entry = {skuId, specId, price, stock}。skuMap 缺失/空 → 空列表（上游异常形态留 raw）。
     *
     * <p>entry 的 {@code specId} <b>暂不映射</b>（sourceSpecId 传 null）：其语义存疑（本仓 fixture 里两条
     * 不同 SKU 的 specId 取值相同，实为规格维度的 id），采集侧填充属 #23——届时用真实响应校准后决定
     * 取哪个字段。现在透传会把未验证的语义固化进 fixture。
     */
    private List<OfferData.OfferSku> mapSkus(JsonNode skuInfo) {
        List<OfferData.OfferSku> skus = new ArrayList<>();
        JsonNode skuMap = skuInfo.path("skuMap");
        if (!skuMap.isObject()) {
            return skus;
        }
        Iterator<String> keys = skuMap.fieldNames();
        while (keys.hasNext()) {
            String specText = keys.next();
            JsonNode entry = skuMap.get(specText);
            String sourceSkuId = text(entry, "skuId");
            JsonNode priceNode = entry.path("price");
            Money price = priceNode.isNumber()
                    ? new Money(formatAmount(priceNode), CURRENCY_CNY)
                    : null;
            skus.add(new OfferData.OfferSku(sourceSkuId, null, specText, parseSpecText(specText), price));
        }
        return skus;
    }

    /**
     * 规格组合键尽力解析为 name/value 对（"颜色:黑色;尺码:L" → [{颜色,黑色},{尺码,L}]）。
     * 分隔符：半角/全角分号；组内首个半角/全角冒号切分。不可解析的分组跳过（原文仍在
     * specText 与 raw，不丢）。1688 组合键格式随 offer 变化——#23 实测校准。
     */
    static List<SpecValue> parseSpecText(String specText) {
        List<SpecValue> specs = new ArrayList<>();
        if (specText == null || specText.isBlank()) {
            return specs;
        }
        for (String group : specText.split("[;；]")) {
            String trimmed = group.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] kv = trimmed.split("[:：]", 2);
            if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
                specs.add(new SpecValue(kv[0].trim(), kv[1].trim()));
            }
        }
        return specs;
    }

    /** 1688 categoryId/categoryName（可选；字段名 #23 实测校准）。 */
    private List<CategoryRef> mapCategories(JsonNode offer) {
        String categoryId = text(offer, "categoryId");
        if (categoryId == null || categoryId.isBlank()) {
            return List.of();
        }
        String label = text(offer, "categoryName");
        return List.of(new CategoryRef("1688", categoryId, label));
    }

    /** 1688 attributes[]（可选；{name, value?, unit?}，value 为 scalar，字段名 #23 实测校准）。 */
    private List<Attribute> mapAttributes(JsonNode offer) {
        JsonNode attributes = offer.path("attributes");
        if (!attributes.isArray()) {
            return List.of();
        }
        List<Attribute> result = new ArrayList<>();
        for (JsonNode attr : attributes) {
            String key = text(attr, "name");
            if (key == null || key.isBlank()) {
                continue;
            }
            JsonNode value = attr.get("value");
            if (value == null || !value.isValueNode()) {
                continue;
            }
            result.add(new Attribute(key, value, text(attr, "unit")));
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() ? value.asText() : null;
    }

    /** 金额：decimal 数值去除尾零后的可读字符串（45.90 → "45.9"），避免浮点文本噪音。 */
    private static String formatAmount(JsonNode price) {
        return price.decimalValue().stripTrailingZeros().toPlainString();
    }
}
