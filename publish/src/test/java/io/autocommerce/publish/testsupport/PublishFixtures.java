package io.autocommerce.publish.testsupport;

import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.ListingImage;
import io.autocommerce.core.catalog.model.ListingSku;
import io.autocommerce.core.catalog.model.MediaRole;
import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.catalog.model.Provenance;
import io.autocommerce.core.catalog.model.ProvenanceStep;

import java.util.List;
import java.util.Map;

/**
 * 铺货链测试 fixture（demo / 单测共用）：构造一张"内容就绪"的 {@link Listing}。
 *
 * <p>{@code listingId = listing-{spuId}-{channelId}}（对齐 {@code PublishRuntime.workflowIdFor} 与
 * 内容链 {@code ListingDraftFactory} 的确定性口径）。
 */
public final class PublishFixtures {

    /** fixture 铺货平台标识（fixture 假 adapter 的 platform）。 */
    public static final String PLATFORM = "fixture-publish";

    public static final String SPU_ID = "spu-1688-7001";
    public static final String CHANNEL_ID = "taobao-shop-a";
    public static final String LISTING_ID = "listing-" + SPU_ID + "-" + CHANNEL_ID;

    /** PUBLISHED 事实时间（供断言；非 workflow 时钟）。 */
    public static final String PUBLISHED_AT = "2026-09-10T08:00:00Z";

    private PublishFixtures() {
    }

    /** 内容就绪 Listing（铺货 workflow 唯一输入）。 */
    public static Listing listing() {
        return new Listing(
                LISTING_ID,
                SPU_ID,
                CHANNEL_ID,
                Map.of("zh-CN", "便携蓝牙音箱 户外防水"),
                Map.of("zh-CN", "迷你无线低音炮，IPX7 防水，续航 12 小时"),
                List.of("zh-CN"),
                new CategoryRef("taobao", "5001", "数码/影音"),
                List.of(),
                List.of(),
                List.of(new ListingSku("sku-3001", new Money("82.62", "CNY"), true)),
                List.of(new ListingImage("media-1", MediaRole.MAIN, null)),
                List.of(),
                new Provenance(ProvenanceStep.LISTING, null, SPU_ID,
                        "2026-09-10T00:00:00Z", "2026-09-10T00:00:00Z"));
    }
}
