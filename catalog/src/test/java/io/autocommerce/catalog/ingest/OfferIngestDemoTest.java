package io.autocommerce.catalog.ingest;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractObjectMapper;
import io.autocommerce.core.testutil.ContractSchemas;
import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.catalog.store.JsonFileCatalogStore;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ServiceLoader;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo harness（#19 AC 末条）：单一采集入口 = 测试 harness——经 Java SPI 装配首个 1688 Adapter
 * 的 OfferFetchCapability → 拉取一份 1688 offer fixture（WireMock 模拟网关）→ catalog ingest →
 * master product 文档落 JsonFileCatalogStore（target/catalog-demo/{spuId}.json，落库可见）。
 *
 * <p>master 文档经 product-catalog schema 契约校验（Testing §1）。fixture 与 adapter-1688
 * 模块的 platform→standard fixture 同源镜像（该模块是映射语义的单一事实源，#23 实测校准随它改）。
 */
class OfferIngestDemoTest {

    private static final String ENDPOINT = "/openapi/param2/1/com.alibaba.product/alibaba.product.get";

    @Test
    void fetch1688OfferAndPersistMaster() throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            server.stubFor(post(urlEqualTo(ENDPOINT))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(demoFixture())));

            // SPI 装配（无中央注册表）：classpath 发现 adapter-1688 provider
            PlatformAdapterProvider provider = ServiceLoader.load(PlatformAdapterProvider.class)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .filter(p -> "1688".equals(p.platform()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("classpath 未发现 1688 adapter provider"
                            + "（adapter-1688 是否在 test classpath?）"));
            assertThat(provider.capabilities()).contains(OfferFetchCapability.class);
            OfferFetchCapability offerFetch = provider.getCapability(OfferFetchCapability.class);

            CatalogStore store = new JsonFileCatalogStore(Path.of("target", "catalog-demo"));
            CatalogIngestService service = new CatalogIngestService(offerFetch, store);

            SourceRef sourceRef = new SourceRef("1688", "6688990011",
                    "http://localhost:" + server.port() + ENDPOINT, "2026-09-10T00:00:00Z");
            CatalogIngestService.IngestResult result = service.ingest(sourceRef);

            // —— SPU master 落库可见 ——
            assertThat(result.spuId()).isEqualTo("spu-1688-6688990011");
            assertThat(result.skuIds()).hasSize(2);
            assertThat(store.listSpuIds()).containsExactly("spu-1688-6688990011");
            ProductCatalog doc = store.get("spu-1688-6688990011").orElseThrow();

            // 关键落库要素：source_ref 一等字段 / specs[] 结构化 / 来源属性 JSONB /
            // 来源类目挂载 / 对象级 provenance / platform_raw 逃生口
            assertThat(doc.spus().get(0).sourceRef()).isEqualTo(sourceRef);
            assertThat(doc.spus().get(0).titles()).containsKey("zh-CN");
            assertThat(doc.spus().get(0).sourceCategories())
                    .extracting("taxonomy").containsExactly("1688");
            assertThat(doc.spus().get(0).attributes()).isNotEmpty();
            assertThat(doc.spus().get(0).platformRaw()).isNotNull();
            assertThat(doc.skus().get(0).specs()).isNotEmpty();
            assertThat(doc.spus().get(0).provenance().createdByStep().name()).isEqualTo("CAPTURE");
            assertThat(doc.mediaAssets()).allMatch(m -> m.processingState().name().equals("RAW"));

            // 契约门：master 数据通过 product-catalog schema 校验
            ContractAssertions.assertValid(ContractSchemas.productCatalog(),
                    ContractObjectMapper.create().valueToTree(doc), "demo master 文档");

            // 落点提示（人工检视）
            System.out.println("[demo] SPU master 已落库: target/catalog-demo/" + result.spuId() + ".json"
                    + "（sku=" + result.skuIds().size() + ", media=" + result.mediaIds().size() + "）");
        } finally {
            server.stop();
        }
    }

    private static String demoFixture() throws Exception {
        try (InputStream in = OfferIngestDemoTest.class
                .getResourceAsStream("/fixtures/1688-offer-demo.json")) {
            assertThat(in).as("demo fixture 缺失").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
