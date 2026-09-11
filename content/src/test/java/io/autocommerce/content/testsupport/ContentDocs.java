package io.autocommerce.content.testsupport;

import io.autocommerce.core.catalog.model.Attribute;
import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.MediaAsset;
import io.autocommerce.core.catalog.model.MediaRef;
import io.autocommerce.core.catalog.model.MediaRole;
import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.ProcessingState;
import io.autocommerce.core.catalog.model.Provenance;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.catalog.model.Sku;
import io.autocommerce.core.catalog.model.SkuRef;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.catalog.model.SpecValue;
import io.autocommerce.core.catalog.model.Spu;
import io.autocommerce.content.listing.ListingDraftFactory;
import io.autocommerce.core.testutil.ContractObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 测试文档构造（内容链测试的公共素材）：一份 master（1 SPU + 2 SKU + 2 图，形态对齐 #19 采集产物）
 * 与在其上装配的待就绪 Listing。
 */
public final class ContentDocs {

    public static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = ContractObjectMapper.create();

    public static final String SPU_ID = "spu-1688-6688990011";
    public static final String CHANNEL_ID = "taobao-shop-a";
    public static final String SOURCE_LOCALE = "zh-CN";
    public static final String SOURCE_TITLE = "无线蓝牙耳机 降噪 长续航";

    private ContentDocs() {
    }

    /** master 文档（无 Listing；等价于 #19 采集落库形态）。 */
    public static ProductCatalog master() {
        Provenance capture = new Provenance(ProvenanceStep.CAPTURE, null, "offer-6688990011",
                "2026-09-10T08:00:00Z", "2026-09-10T08:00:00Z");
        List<Sku> skus = List.of(
                new Sku("sku-1688-6688990011-1", SPU_ID, List.of(new SpecValue("color", "black")),
                        new Money("45.90", "CNY"), null, null, "1", null, capture),
                new Sku("sku-1688-6688990011-2", SPU_ID, List.of(new SpecValue("color", "white")),
                        new Money("52.00", "CNY"), null, null, "2", null, capture));
        List<MediaAsset> media = List.of(
                new MediaAsset("media-1688-6688990011-0", "http://localhost:1/main.jpg", null,
                        MediaRole.MAIN, ProcessingState.RAW, null, null, null, capture),
                new MediaAsset("media-1688-6688990011-1", "http://localhost:1/gallery.jpg", null,
                        MediaRole.GALLERY, ProcessingState.RAW, null, null, null, capture));
        Spu spu = new Spu(SPU_ID,
                new SourceRef("1688", "6688990011", "http://localhost:1/offer", "2026-09-10T08:00:00Z"),
                Map.of(SOURCE_LOCALE, SOURCE_TITLE),
                null,
                List.of(new MediaRef("media-1688-6688990011-0", MediaRole.MAIN),
                        new MediaRef("media-1688-6688990011-1", MediaRole.GALLERY)),
                List.of(new SkuRef("sku-1688-6688990011-1"), new SkuRef("sku-1688-6688990011-2")),
                List.of(new CategoryRef("1688", "1216", "数码/影音")),
                List.of(new Attribute("电池容量", MAPPER.getNodeFactory().textNode("1200mAh"), "mAh")),
                null, capture);
        return new ProductCatalog("0.1.0", List.of(spu), skus, List.of(), media);
    }

    /** master + 待就绪 Listing（内容链输入；listingId = listing-{spuId}-{channelId}）。 */
    public static ProductCatalog masterWithListing(List<String> locales) {
        return new ListingDraftFactory(FIXED_CLOCK)
                .draft(master(), CHANNEL_ID, new CategoryRef("taobao", "5001", "数码/影音"), locales);
    }

    public static ProductCatalog masterWithListing() {
        return masterWithListing(List.of(SOURCE_LOCALE, "en"));
    }

    public static String listingId() {
        return "listing-" + SPU_ID + "-" + CHANNEL_ID;
    }

    public static Listing listing(ProductCatalog document, String listingId) {
        return document.listings().stream()
                .filter(l -> l.listingId().equals(listingId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("文档内无 Listing: " + listingId));
    }

    /** 把文档内媒体源 URL 指向本地 fake 端点（demo/需要真实下载的测试用）。 */
    public static ProductCatalog withMediaBaseUrl(ProductCatalog document, String baseUrl) {
        List<MediaAsset> media = new ArrayList<>();
        for (MediaAsset asset : document.mediaAssets()) {
            media.add(new MediaAsset(asset.mediaId(), baseUrl + "/media/" + asset.mediaId() + ".jpg",
                    asset.storageRef(), asset.role(), asset.processingState(), asset.variantOf(),
                    asset.variantPurpose(), asset.variantLocale(), asset.provenance()));
        }
        return new ProductCatalog(document.schemaVersion(), document.spus(), document.skus(),
                document.listings(), media);
    }
}
