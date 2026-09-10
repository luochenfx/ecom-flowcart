package io.autocommerce.catalog.ingest;

import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.contract.AdapterErrorKind;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.dto.OfferData;
import io.autocommerce.catalog.store.CatalogStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CatalogIngestService：采集入口（fake OfferFetchCapability + fake store，无网络 / 无框架 mock）。
 * 验证：经能力接口拉取 → mapper 组装 → CatalogStore.put；确定性 spuId（幂等覆盖）；
 * AdapterException 原样上抛（错误纪律，Testing §6）。
 */
class CatalogIngestServiceTest {

    private static final SourceRef REF = new SourceRef("1688", "6688990011",
            "https://detail.1688.com/offer/6688990011.html", "2026-09-10T00:00:00Z");

    @Test
    void ingestsOfferIntoMasterStore() {
        FakeOfferFetch fetch = new FakeOfferFetch(demoOfferData());
        MapStore store = new MapStore();
        CatalogIngestService service = new CatalogIngestService(fetch, store);

        CatalogIngestService.IngestResult result = service.ingest(REF);

        assertThat(fetch.calls).isEqualTo(1);
        assertThat(result.spuId()).isEqualTo("spu-1688-6688990011");
        assertThat(result.skuIds()).containsExactly(
                "sku-1688-6688990011-523681097354", "sku-1688-6688990011-523681097355");
        assertThat(result.mediaIds()).containsExactly("media-1688-6688990011-0");

        assertThat(store.docs).containsOnlyKeys("spu-1688-6688990011");
        ProductCatalog doc = store.docs.get("spu-1688-6688990011");
        assertThat(doc.spus().get(0).sourceRef()).isEqualTo(REF);
        assertThat(doc.spus().get(0).titles()).containsKey("zh-CN");
        assertThat(doc.spus().get(0).provenance().createdByStep().name()).isEqualTo("CAPTURE");
    }

    @Test
    void reingestSameOfferOverwritesSameSpu() {
        FakeOfferFetch fetch = new FakeOfferFetch(demoOfferData());
        MapStore store = new MapStore();
        CatalogIngestService service = new CatalogIngestService(fetch, store);

        service.ingest(REF);
        service.ingest(REF);

        assertThat(fetch.calls).isEqualTo(2);
        assertThat(store.docs).hasSize(1);   // 幂等覆盖：不产生重复 master
    }

    @Test
    void adapterExceptionPropagatesUnwrapped() {
        OfferFetchCapability failing = ref -> {
            throw AdapterException.nonRetryable("isv.invalid-parameter", "商品不存在或已删除");
        };
        CatalogIngestService service = new CatalogIngestService(failing, new MapStore());

        assertThatThrownBy(() -> service.ingest(REF))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("isv.invalid-parameter");
                });
    }

    // ---- hand-written fakes（本仓库测试纪律：不用 mock 框架） ----

    private static final class FakeOfferFetch implements OfferFetchCapability {
        private final OfferData data;
        private int calls;

        FakeOfferFetch(OfferData data) {
            this.data = data;
        }

        @Override
        public OfferData fetchOffer(SourceRef ref) {
            calls++;
            return data;
        }
    }

    private static final class MapStore implements CatalogStore {
        private final Map<String, ProductCatalog> docs = new LinkedHashMap<>();

        @Override
        public void put(ProductCatalog product) {
            docs.put(product.spus().get(0).spuId(), product);
        }

        @Override
        public Optional<ProductCatalog> get(String spuId) {
            return Optional.ofNullable(docs.get(spuId));
        }

        @Override
        public List<String> listSpuIds() {
            return new ArrayList<>(docs.keySet());
        }
    }

    private static OfferData demoOfferData() {
        Money price = new Money("45.9", "CNY");
        return new OfferData(
                "6688990011",
                "便携蓝牙音箱 迷你无线低音炮 户外防水",
                List.of("https://cbu01.alicdn.com/img/ibank/2026/001/001/0000000001.jpg"),
                List.of(
                        new OfferData.OfferSku("523681097354", "颜色:黑色", List.of(), price),
                        new OfferData.OfferSku("523681097355", "颜色:白色", List.of(), price)),
                List.of(), List.of(), null);
    }
}
