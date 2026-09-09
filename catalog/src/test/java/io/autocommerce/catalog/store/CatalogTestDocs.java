package io.autocommerce.catalog.store;

import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.Provenance;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.catalog.model.Spu;

import java.util.List;
import java.util.Map;

/** 测试文档构造助手（最小单 SPU product 文档）。 */
final class CatalogTestDocs {

    private CatalogTestDocs() {
    }

    static ProductCatalog singleSpuDocument(String spuId) {
        Provenance capture = new Provenance(ProvenanceStep.CAPTURE, null, "offer-x",
                "2026-09-10T00:00:00Z", "2026-09-10T00:00:00Z");
        Spu spu = new Spu(spuId,
                new SourceRef("1688", "6688990011",
                        "https://detail.1688.com/offer/6688990011.html", "2026-09-10T00:00:00Z"),
                Map.of("zh-CN", "便携蓝牙音箱"),
                null, List.of(), List.of(), List.of(), List.of(), null, capture);
        return new ProductCatalog("0.1.0", List.of(spu), List.of(), List.of(), List.of());
    }
}
