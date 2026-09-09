package io.autocommerce.adapter.ali1688;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.catalog.model.Attribute;
import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.catalog.model.SpecValue;
import io.autocommerce.core.contract.AdapterErrorKind;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.OfferData;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * platform → standard 结构转换 fixture 测试（Testing Decisions §3 起步，随首个 Adapter 子集）。
 *
 * <p>1688 {@code alibaba.product.get} 响应 fixture（fixtures/1688-offer-response.json）→
 * 平台无关采集面 OfferData。fixture = 本子集承诺的映射语义；categoryId/categoryName/attributes[]
 * 字段名与规格组合串解析规则待 #23 真实 API 实测校准（见 mapper javadoc）。
 */
class Ali1688OfferJsonMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Ali1688OfferJsonMapper mapper = new Ali1688OfferJsonMapper(MAPPER);

    @Test
    void mapsWrapperToOfferData() throws Exception {
        OfferData offer = mapper.map(fixture());

        assertThat(offer.externalId()).isEqualTo("6688990011");
        assertThat(offer.title()).isEqualTo("便携蓝牙音箱 迷你无线低音炮 户外防水");
        assertThat(offer.imageUrls()).containsExactly(
                "https://cbu01.alicdn.com/img/ibank/2026/001/001/0000000001.jpg");

        // skuInfo.skuMap → OfferSku：sourceSkuId / 组合键原文 / 尽力结构化 specs / 价格
        assertThat(offer.skus()).hasSize(2);
        OfferData.OfferSku black = offer.skus().get(0);
        assertThat(black.sourceSkuId()).isEqualTo("523681097354");
        assertThat(black.specText()).isEqualTo("颜色:黑色");
        assertThat(black.specs()).containsExactly(new SpecValue("颜色", "黑色"));
        assertThat(black.price()).isEqualTo(new Money("45.9", "CNY"));
        assertThat(offer.skus().get(1).specs()).containsExactly(new SpecValue("颜色", "白色"));

        // 来源类目挂载（taxonomy=1688）与来源属性 JSONB 键值（scalar 保真）
        assertThat(offer.sourceCategories())
                .containsExactly(new CategoryRef("1688", "1601", "数码/影音/音箱"));
        assertThat(offer.attributes()).hasSize(3);
        Attribute battery = offer.attributes().get(0);
        assertThat(battery.key()).isEqualTo("电池容量");
        assertThat(battery.value().asText()).isEqualTo("1200mAh");
        assertThat(battery.unit()).isEqualTo("mAh");
        assertThat(offer.attributes().get(1).value().isNumber()).isTrue();
        assertThat(offer.attributes().get(2).value().isBoolean()).isTrue();

        // raw = offer 子树逃生口：标准模型未覆盖字段（priceRanges/status/skuInfo.specs 定义）直通
        JsonNode raw = offer.raw();
        assertThat(raw).isNotNull();
        assertThat(raw.path("priceRanges").get(0).path("price").decimalValue())
                .isEqualByComparingTo("45.90");
        assertThat(raw.path("status").asText()).isEqualTo("published");
        assertThat(raw.path("skuInfo").path("specs").isArray()).isTrue();
    }

    @Test
    void mapsBareOfferWithoutWrapper() throws Exception {
        // 无 success/result 包装的裸 offer（fixture/直连形态）同样可映射
        JsonNode offerNode = MAPPER.readTree(fixture()).path("result");
        OfferData offer = mapper.map(offerNode.toString());

        assertThat(offer.externalId()).isEqualTo("6688990011");
        assertThat(offer.raw().path("subject").asText())
                .isEqualTo("便携蓝牙音箱 迷你无线低音炮 户外防水");
    }

    @Test
    void businessErrorMapsToNonRetryable() {
        String wrapper = "{\"success\":false,\"errorCode\":\"isv.invalid-parameter\","
                + "\"errorMsg\":\"商品不存在或已删除\"}";

        assertThatThrownBy(() -> mapper.map(wrapper))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("isv.invalid-parameter");
                    assertThat(e.getMessage()).contains("商品不存在或已删除");
                });
    }

    @Test
    void parseSpecTextHandlesFullWidthAndSkipsUnparsableGroups() {
        // 半角/全角分隔符混用；无冒号分组跳过（原文不丢：specText 仍在 OfferData）
        List<SpecValue> specs = Ali1688OfferJsonMapper.parseSpecText("颜色:红色；尺码：L");
        assertThat(specs).containsExactly(new SpecValue("颜色", "红色"), new SpecValue("尺码", "L"));

        assertThat(Ali1688OfferJsonMapper.parseSpecText("红色,L")).isEmpty();
        assertThat(Ali1688OfferJsonMapper.parseSpecText(null)).isEmpty();
    }

    private String fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/1688-offer-response.json")) {
            assertThat(in).as("fixture 缺失").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
