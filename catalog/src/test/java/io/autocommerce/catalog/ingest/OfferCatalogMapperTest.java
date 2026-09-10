package io.autocommerce.catalog.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.catalog.model.Attribute;
import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.MediaRole;
import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.ProcessingState;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.catalog.model.Sku;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.catalog.model.SpecValue;
import io.autocommerce.core.catalog.model.Spu;
import io.autocommerce.core.contract.dto.OfferData;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractObjectMapper;
import io.autocommerce.core.testutil.ContractSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OfferCatalogMapper：采集面 OfferData → master product 文档（SPU/SKU/MediaAsset）。
 * 断言 master 组装规则（确定性 id / locale zh-CN / 来源类目与属性挂载 / platform_raw /
 * provenance CAPTURE），并过 product-catalog schema 契约校验（Testing §1）。
 */
class OfferCatalogMapperTest {

    private static final String FETCHED_AT = "2026-09-10T00:00:00Z";
    private static final ObjectMapper MAPPER = ContractObjectMapper.create();

    private final OfferCatalogMapper mapper = new OfferCatalogMapper();

    @Test
    void mapsOfferDataToMasterDocument() throws Exception {
        JsonNode raw = MAPPER.readTree("{\"status\":\"published\","
                + "\"priceRanges\":[{\"startQuantity\":1,\"price\":45.9}]}");
        OfferData data = new OfferData(
                "6688990011",
                "便携蓝牙音箱 迷你无线低音炮 户外防水",
                List.of("https://cbu01.alicdn.com/img/ibank/2026/001/001/0000000001.jpg",
                        "https://cbu01.alicdn.com/img/ibank/2026/001/001/0000000002.jpg"),
                List.of(
                        new OfferData.OfferSku("523681097354", "b266e0726506185beaf205cbae88530d", "颜色:黑色",
                                List.of(new SpecValue("颜色", "黑色")), new Money("45.9", "CNY")),
                        new OfferData.OfferSku("523681097355", null, "颜色:白色",
                                List.of(new SpecValue("颜色", "白色")), new Money("45.9", "CNY"))),
                List.of(new CategoryRef("1688", "1601", "数码/影音/音箱")),
                List.of(new Attribute("电池容量", MAPPER.getNodeFactory().textNode("1200mAh"), "mAh"),
                        new Attribute("防水等级", MAPPER.getNodeFactory().numberNode(5), null)),
                raw);
        SourceRef ref = new SourceRef("1688", "6688990011",
                "https://detail.1688.com/offer/6688990011.html", FETCHED_AT);

        ProductCatalog doc = mapper.map(ref, data);

        // —— 结构与确定性 id（重采同 offer → 同 id，幂等覆盖） ——
        Spu spu = doc.spus().get(0);
        assertThat(spu.spuId()).isEqualTo("spu-1688-6688990011");
        assertThat(spu.sourceRef()).isEqualTo(ref);
        assertThat(spu.titles()).containsExactlyEntriesOf(
                java.util.Map.of("zh-CN", "便携蓝牙音箱 迷你无线低音炮 户外防水"));
        assertThat(spu.descriptions()).isNull();
        assertThat(doc.mediaAssets()).hasSize(2);
        assertThat(spu.images()).extracting("mediaId")
                .containsExactly("media-1688-6688990011-0", "media-1688-6688990011-1");
        assertThat(doc.mediaAssets().get(0).role()).isEqualTo(MediaRole.MAIN);
        assertThat(doc.mediaAssets().get(0).processingState()).isEqualTo(ProcessingState.RAW);
        assertThat(doc.mediaAssets().get(1).role()).isEqualTo(MediaRole.GALLERY);

        assertThat(doc.skus()).hasSize(2);
        Sku first = doc.skus().get(0);
        assertThat(first.skuId()).isEqualTo("sku-1688-6688990011-523681097354");
        assertThat(first.spuId()).isEqualTo("spu-1688-6688990011");
        assertThat(first.sourceSkuId()).isEqualTo("523681097354");
        // 货源侧标识符照搬（#39）：sourceSpecId（1688 下单键）随 sourceSkuId 一同到 master，
        // 采集侧未填充时原样透传 null（值侧来源校准属 #23）
        assertThat(first.sourceSpecId()).isEqualTo("b266e0726506185beaf205cbae88530d");
        assertThat(doc.skus().get(1).sourceSpecId()).isNull();
        assertThat(first.specs()).containsExactly(new SpecValue("颜色", "黑色"));
        assertThat(first.costPrice()).isEqualTo(new Money("45.9", "CNY"));
        assertThat(first.barcode()).isNull();

        // —— 来源类目挂载 / 来源属性 JSONB / platform_raw 逃生口 ——
        assertThat(spu.sourceCategories())
                .containsExactly(new CategoryRef("1688", "1601", "数码/影音/音箱"));
        assertThat(spu.attributes()).hasSize(2);
        assertThat(spu.attributes().get(0).value().asText()).isEqualTo("1200mAh");
        assertThat(spu.attributes().get(1).value().isNumber()).isTrue();
        assertThat(spu.platformRaw()).isSameAs(raw);

        // —— 对象级 provenance：CAPTURE（采集原貌）—— 
        assertThat(spu.provenance().createdByStep()).isEqualTo(ProvenanceStep.CAPTURE);
        assertThat(spu.provenance().updatedByStep()).isNull();
        assertThat(spu.provenance().parentRef()).isEqualTo("offer-6688990011");
        assertThat(spu.provenance().createdAt()).isEqualTo(FETCHED_AT);
        assertThat(spu.provenance().updatedAt()).isEqualTo(FETCHED_AT);
        assertThat(doc.skus().get(0).provenance().parentRef()).isNull();

        // —— 契约门：master 文档过 product-catalog schema ——
        ContractAssertions.assertValid(ContractSchemas.productCatalog(),
                MAPPER.valueToTree(doc), "catalog master 文档");
    }

    @Test
    void sameInputProducesIdenticalDocument() {
        OfferData data = new OfferData("6688990011", "标题", List.of("https://img/a.jpg"),
                List.of(new OfferData.OfferSku("s1", null, "颜色:红",
                        List.of(new SpecValue("颜色", "红")), new Money("10", "CNY"))),
                List.of(), List.of(),
                MAPPER.createObjectNode().put("status", "published"));
        SourceRef ref = new SourceRef("1688", "6688990011", "https://detail.1688.com/offer/x.html",
                FETCHED_AT);

        assertThat(mapper.map(ref, data)).isEqualTo(mapper.map(ref, data));
    }

    @Test
    void missingFetchedAtIsRejected() {
        SourceRef ref = new SourceRef("1688", "1", "https://detail.1688.com/offer/1.html", null);
        OfferData data = new OfferData("1", "t", List.of(), List.of(), List.of(), List.of(), null);

        assertThatThrownBy(() -> mapper.map(ref, data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fetched_at");
    }
}
