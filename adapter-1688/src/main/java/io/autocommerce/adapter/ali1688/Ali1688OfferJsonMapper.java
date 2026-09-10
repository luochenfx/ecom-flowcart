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
        String externalId = Ali1688Json.text(offer, "productId");
        if (externalId == null || externalId.isBlank()) {
            throw AdapterException.nonRetryable("missing-product-id", "offer 缺少 productId");
        }
        String title = Ali1688Json.text(offer, "subject");

        List<String> imageUrls = new ArrayList<>();
        String mainImage = Ali1688Json.text(offer, "imageUrl");
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
     * <p><b>sourceSpecId 值侧来源</b>（#23 落地）：主路径读 skuMap 条目自身的 {@code specId}
     * ——官方 apidoc 的 {@code cargoParamList[]} 示例即 {@code {"specId":"b266e0726506185beaf205cbae88530d"}}
     * （32 位十六进制串），与 skuMap 条目的 specId 同形，故该字段才是<b>逐 SKU 的下单键</b>。
     * 条目缺 specId 时退到 {@link #assembleFromValueIds}（按 {@code skuInfo.specs} 维度顺序取
     * {@code values[].valueId}）。
     *
     * <p>注意 {@code skuInfo.specs[].specId} 是<b>规格维度的 id</b>（如"颜色"这一个维度），
     * 不是逐 SKU 的下单键——本仓旧 fixture 把两者写成同值即由此误读而来，已校正为
     * 「skuMap 条目 specId 逐 SKU 不同 + 维度 specId 另值」的 32-hex 形态。
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
            String sourceSkuId = Ali1688Json.text(entry, "skuId");
            String sourceSpecId = resolveSourceSpecId(entry, skuInfo, specText);
            JsonNode priceNode = entry.path("price");
            Money price = priceNode.isNumber()
                    ? new Money(formatAmount(priceNode), CURRENCY_CNY)
                    : null;
            skus.add(new OfferData.OfferSku(sourceSkuId, sourceSpecId, specText,
                    parseSpecText(specText), price));
        }
        return skus;
    }

    /**
     * 1688 下单键 {@code specId} 的取值（#23）：① skuMap 条目自带 {@code specId} → 直接用；
     * ② 否则按 {@code skuInfo.specs} 的维度顺序，取每个维度中与 {@code specText} 匹配的
     * {@code values[].valueId}，以 {@code ;} 连接。
     *
     * <p><b>② 的形态未经真实响应验证</b>：单一维度时它退化为「该维度值 id」，多维度组合是否
     * 真以 {@code ;} 连接属未知（官方文档只给了 skuMap 形态的 32-hex 单串）。真实沙箱校准后
     * 若 1688 无此组装形态，应改为不填充（让下单在缺必填项处显式失败），而不是固化本猜测。
     * 两条路径都拿不到 → null，由 {@code PurchaseDraftItem} 构造侧按必填缺口报错。
     */
    static String resolveSourceSpecId(JsonNode skuMapEntry, JsonNode skuInfo, String specText) {
        String direct = Ali1688Json.text(skuMapEntry, "specId");
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        return assembleFromValueIds(skuInfo, specText);
    }

    /** 按 {@code skuInfo.specs} 维度顺序，把 specText 里各维度的值映射成 {@code values[].valueId} 并连接。 */
    private static String assembleFromValueIds(JsonNode skuInfo, String specText) {
        JsonNode specs = skuInfo.path("specs");
        if (!specs.isArray() || specText == null || specText.isBlank()) {
            return null;
        }
        List<SpecValue> parsed = parseSpecText(specText);
        List<String> valueIds = new ArrayList<>();
        for (JsonNode dimension : specs) {
            String dimensionName = Ali1688Json.text(dimension, "name");
            String matchedValue = valueOf(parsed, dimensionName);
            if (matchedValue == null) {
                continue;
            }
            String valueId = valueIdOf(dimension.path("values"), matchedValue);
            if (valueId != null) {
                valueIds.add(valueId);
            }
        }
        return valueIds.isEmpty() ? null : String.join(";", valueIds);
    }

    /** 从"规格维度名 → 规格值"解析结果里取值；维度名缺失/未命中 → null。 */
    private static String valueOf(List<SpecValue> parsed, String dimensionName) {
        if (dimensionName == null) {
            return null;
        }
        return parsed.stream()
                .filter(spec -> dimensionName.equals(spec.name()))
                .map(SpecValue::value)
                .findFirst()
                .orElse(null);
    }

    /** 在 dimensions 的 values[] 中按 name 找 valueId；未命中 → null。 */
    private static String valueIdOf(JsonNode values, String valueName) {
        if (!values.isArray()) {
            return null;
        }
        for (JsonNode value : values) {
            if (valueName.equals(Ali1688Json.text(value, "name"))) {
                String valueId = Ali1688Json.text(value, "valueId");
                if (valueId != null && !valueId.isBlank()) {
                    return valueId;
                }
            }
        }
        return null;
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
        String categoryId = Ali1688Json.text(offer, "categoryId");
        if (categoryId == null || categoryId.isBlank()) {
            return List.of();
        }
        String label = Ali1688Json.text(offer, "categoryName");
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
            String key = Ali1688Json.text(attr, "name");
            if (key == null || key.isBlank()) {
                continue;
            }
            JsonNode value = attr.get("value");
            if (value == null || !value.isValueNode()) {
                continue;
            }
            result.add(new Attribute(key, value, Ali1688Json.text(attr, "unit")));
        }
        return result;
    }

    /** 金额：decimal 数值去除尾零后的可读字符串（45.90 → "45.9"），避免浮点文本噪音。 */
    private static String formatAmount(JsonNode price) {
        return price.decimalValue().stripTrailingZeros().toPlainString();
    }
}
