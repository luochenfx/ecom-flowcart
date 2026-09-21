package io.autocommerce.catalog.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.catalog.model.Attribute;
import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.MediaAsset;
import io.autocommerce.core.catalog.model.MediaRef;
import io.autocommerce.core.catalog.model.MediaRole;
import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.catalog.model.ProcessingState;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.Provenance;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.catalog.model.Sku;
import io.autocommerce.core.catalog.model.SkuRef;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.catalog.model.SpecValue;
import io.autocommerce.core.catalog.model.Spu;

import java.util.List;
import java.util.Map;

/** 测试文档构造助手（最小单 SPU product 文档 + 含嵌套集合 / 可空字段的富文档）。 */
final class CatalogTestDocs {

    private CatalogTestDocs() {
    }

    /** 最小单 SPU 文档（与 JsonFileCatalogStoreTest 既有用法一致）。 */
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

    /**
     * 富文档：单 SPU + 嵌套集合（images / skus / attributes / categories）+ 可空字段 + SKU /
     * MediaAsset / Listing 全量。用于落库再取回的保真断言（JSONB 内嵌套结构不得丢失）。
     */
    static ProductCatalog richSingleSpuDocument(String spuId) {
        Provenance capture = new Provenance(ProvenanceStep.CAPTURE, null, "offer-x",
                "2026-09-10T00:00:00Z", "2026-09-10T00:00:00Z");
        ObjectMapper json = new ObjectMapper();

        Spu spu = new Spu(spuId,
                new SourceRef("1688", "6688990011",
                        "https://detail.1688.com/offer/6688990011.html", "2026-09-10T00:00:00Z"),
                Map.of("zh-CN", "便携蓝牙音箱", "en-US", "Portable Bluetooth Speaker"),
                Map.of("zh-CN", "高保真便携音箱"),
                List.of(new MediaRef("media-1", MediaRole.MAIN),
                        new MediaRef("media-2", MediaRole.GALLERY)),
                List.of(new SkuRef("sku-1"), new SkuRef("sku-2")),
                List.of(new CategoryRef("1688", "5001", "数码")),
                List.of(new Attribute("color", json.getNodeFactory().textNode("black"), null),
                        new Attribute("weight", json.getNodeFactory().numberNode(1.5), "kg")),
                null, capture);

        Sku sku = new Sku("sku-1", spuId,
                List.of(new SpecValue("容量", "500ml")),
                new Money("12.50", "CNY"),
                null,
                List.of(new MediaRef("media-1", MediaRole.MAIN)),
                "source-sku-1", null, capture);

        MediaAsset asset = new MediaAsset("media-1",
                "https://cbu01.alicdn.com/img.jpg", "storage/2026/media-1.jpg",
                MediaRole.MAIN, ProcessingState.DOWNLOADED,
                null, null, null, capture);

        Listing listing = new Listing("listing-1", spuId, "fake-sales",
                Map.of("zh-CN", "标题覆盖"), Map.of(),
                List.of("zh-CN"), null, List.of(), List.of(), List.of(), List.of(), List.of(),
                capture);

        return new ProductCatalog("0.1.0", List.of(spu), List.of(sku), List.of(listing),
                List.of(asset));
    }
}
