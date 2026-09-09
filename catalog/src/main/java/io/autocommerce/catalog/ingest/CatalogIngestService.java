package io.autocommerce.catalog.ingest;

import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.dto.OfferData;
import io.autocommerce.catalog.store.CatalogStore;

import java.util.List;
import java.util.Objects;

/**
 * 采集入口（#19 单一采集路径）：{@code SourceRef} → OfferFetchCapability（Java SPI 装配的
 * 1688 Adapter 子集，禁环② 经 core 接口吸收）→ OfferData → master product 文档 → CatalogStore。
 *
 * <p>失败语义：平台侧错误原样抛 {@link AdapterException}（RETRYABLE / NON_RETRYABLE），
 * 由调用方（后续 workflow / REST）按错误契约决策；非 AdapterException = bug，不吞。
 *
 * <p>幂等：mapper 确定性 id + store.put 按 spuId 覆盖——同 offer 重复采集更新 master，
 * 不产生重复记录（master 刷新语义，与 #11 Listing 幂等正交）。
 */
public final class CatalogIngestService {

    private final OfferFetchCapability offerFetch;
    private final CatalogStore store;
    private final OfferCatalogMapper mapper;

    public CatalogIngestService(OfferFetchCapability offerFetch, CatalogStore store) {
        this(offerFetch, store, new OfferCatalogMapper());
    }

    CatalogIngestService(OfferFetchCapability offerFetch, CatalogStore store, OfferCatalogMapper mapper) {
        this.offerFetch = Objects.requireNonNull(offerFetch, "offerFetch 必填");
        this.store = Objects.requireNonNull(store, "catalog store 必填");
        this.mapper = Objects.requireNonNull(mapper, "mapper 必填");
    }

    /** 采集一个 1688 source offer 并落库，返回本次写入的 master 对象 id 集。 */
    public IngestResult ingest(SourceRef sourceRef) throws AdapterException {
        OfferData data = offerFetch.fetchOffer(sourceRef);
        ProductCatalog doc = mapper.map(sourceRef, data);
        store.put(doc);
        return IngestResult.from(doc);
    }

    /** 本次采集落库结果（demo / 后续 workflow 事件载荷的最小回执）。 */
    public record IngestResult(String spuId, List<String> skuIds, List<String> mediaIds) {

        static IngestResult from(ProductCatalog doc) {
            return new IngestResult(
                    doc.spus().get(0).spuId(),
                    doc.skus().stream().map(s -> s.skuId()).toList(),
                    doc.mediaAssets() == null
                            ? List.of()
                            : doc.mediaAssets().stream().map(m -> m.mediaId()).toList());
        }
    }
}
