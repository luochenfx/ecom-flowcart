package io.autocommerce.core.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.MediaAsset;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.Sku;
import io.autocommerce.core.catalog.model.Spu;
import io.autocommerce.core.catalog.model.VariantPurpose;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractObjectMapper;
import io.autocommerce.core.testutil.ContractSchemas;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testing Decisions §1 双向契约测试（catalog 域）。
 * <ul>
 *   <li>方向 1（JSON → 模型）：golden fixture 反序列化，关键字段逐一断言（无字段丢失）；</li>
 *   <li>方向 2（模型 → JSON）：模型再序列化，须过 product-catalog.schema.json 校验
 *       （机器契约门）且与 golden 语义等价。</li>
 * </ul>
 * schema 单一事实源 = schemas/product-catalog.schema.json（classpath 经 testResources 复制）。
 */
class ProductCatalogContractTest {

    private static final String GOLDEN = "/fixtures/product-catalog.json";

    private final ObjectMapper mapper = ContractObjectMapper.create();

    @Test
    void jsonToModel_keepsAllFields() throws Exception {
        ProductCatalog catalog = mapper.treeToValue(golden(), ProductCatalog.class);

        assertThat(catalog.schemaVersion()).isEqualTo("0.1.0");
        assertThat(catalog.spus()).hasSize(1);
        assertThat(catalog.skus()).hasSize(2);
        assertThat(catalog.listings()).hasSize(1);
        assertThat(catalog.mediaAssets()).hasSize(2);

        Spu spu = catalog.spus().get(0);
        assertThat(spu.spuId()).isEqualTo("spu-1001");
        assertThat(spu.sourceRef().platform()).isEqualTo("1688");
        assertThat(spu.sourceRef().fetchedAt()).isEqualTo("2026-09-09T08:30:00Z");
        assertThat(spu.titles()).containsKeys("zh-CN", "en");
        assertThat(spu.descriptions()).containsOnlyKeys("zh-CN");
        assertThat(spu.images()).hasSize(2);
        assertThat(spu.skus()).extracting("skuId").containsExactly("sku-3001", "sku-3002");
        assertThat(spu.sourceCategories()).hasSize(1);
        assertThat(spu.sourceCategories().get(0).label()).isEqualTo("数码/影音/音箱");
        // 属性值多态（string / number / boolean → JsonNode 保真）
        assertThat(spu.attributes()).hasSize(3);
        assertThat(spu.attributes().get(0).value().asText()).isEqualTo("1200mAh");
        assertThat(spu.attributes().get(1).value().isNumber()).isTrue();
        assertThat(spu.attributes().get(2).value().isBoolean()).isTrue();
        assertThat(spu.provenance().createdByStep()).isEqualTo(io.autocommerce.core.catalog.model.ProvenanceStep.CAPTURE);
        assertThat(spu.provenance().parentRef()).isEqualTo("offer-6688990011");

        // 货源侧标识符落 master（#39）：sourceSpecId = 1688 下单键，与 sourceSkuId（规格组合内部 id）并列
        assertThat(catalog.skus().get(0).sourceSkuId()).isEqualTo("1688-sku-3001");
        assertThat(catalog.skus().get(0).sourceSpecId()).isEqualTo("b266e0726506185beaf205cbae88530d");

        // sku-3002 显式 null 语义（barcode/images/sourceSkuId/sourceSpecId 可空）
        Sku sku2 = catalog.skus().get(1);
        assertThat(sku2.skuId()).isEqualTo("sku-3002");
        assertThat(sku2.barcode()).isNull();
        assertThat(sku2.images()).isNull();
        assertThat(sku2.sourceSkuId()).isNull();
        assertThat(sku2.sourceSpecId()).isNull();
        assertThat(sku2.costPrice().amount()).isEqualTo("45.90");

        Listing listing = catalog.listings().get(0);
        assertThat(listing.listingId()).isEqualTo("listing-4001");
        assertThat(listing.channelId()).isEqualTo("taobao-shop-a");
        assertThat(listing.titleOverrides()).containsOnlyKeys("zh-CN");
        assertThat(listing.locales()).containsExactly("zh-CN", "en");
        assertThat(listing.platformCategory().taxonomy()).isEqualTo("taobao");
        assertThat(listing.platformAttributes()).hasSize(2);
        assertThat(listing.specMappings()).hasSize(1);
        assertThat(listing.skuSet()).hasSize(2);
        assertThat(listing.skuSet().get(0).enabled()).isTrue();
        assertThat(listing.skuSet().get(1).enabled()).isFalse();
        // catalog Money.amount 为 decimal string
        assertThat(listing.skuSet().get(0).price().amount()).isEqualTo("79.00");
        assertThat(listing.images().get(0).platformMediaId()).isEqualTo("tb-media-9001");
        // degraded_steps（#20 内容链降级留痕，specs/0006 §5/§6）：Step id + 原因
        assertThat(listing.degradedSteps()).hasSize(1);
        assertThat(listing.degradedSteps().get(0).step()).isEqualTo("title.rewrite");
        assertThat(listing.degradedSteps().get(0).reason()).isNotBlank();
        assertThat(listing.provenance().createdByStep())
                .isEqualTo(io.autocommerce.core.catalog.model.ProvenanceStep.LISTING);

        MediaAsset main = catalog.mediaAssets().get(0);
        assertThat(main.processingState()).isEqualTo(io.autocommerce.core.catalog.model.ProcessingState.UPLOADED);
        assertThat(main.role()).isEqualTo(io.autocommerce.core.catalog.model.MediaRole.MAIN);
        MediaAsset localized = catalog.mediaAssets().get(1);
        assertThat(localized.variantOf()).isEqualTo("media-2001");
        assertThat(localized.variantPurpose()).isEqualTo(VariantPurpose.LOCALIZED);
        assertThat(localized.variantLocale()).isEqualTo("en");
    }

    @Test
    void modelToJson_passesSchema_andRoundTripsExactly() throws Exception {
        ProductCatalog catalog = mapper.treeToValue(golden(), ProductCatalog.class);

        JsonNode json = mapper.valueToTree(catalog);

        ContractAssertions.assertValid(ContractSchemas.productCatalog(), json, "ProductCatalog 文档");
        ContractAssertions.assertSemanticallyEquals(golden(), json, "ProductCatalog 文档");
    }

    private JsonNode golden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(GOLDEN)) {
            assertThat(in).as("golden fixture 缺失: %s", GOLDEN).isNotNull();
            return mapper.readTree(in);
        }
    }
}
