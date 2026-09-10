package io.autocommerce.content.listing;

import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.ListingImage;
import io.autocommerce.core.catalog.model.ListingSku;
import io.autocommerce.core.catalog.model.MediaRef;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.Provenance;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.catalog.model.Sku;
import io.autocommerce.core.catalog.model.Spu;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Listing 装配（#20 v1 入口）：从 master 文档派生一份**待就绪 Listing**，作为内容链的输入。
 *
 * <p>为什么在 content：内容链的输入就是 Listing（specs/0006 §2），而铺货链（#11/#21）只读就绪
 * Listing —— Listing 的"建单"发生在内容就绪之前，本类即 v1 的这一步。目标叶子类目由调用方给定
 * （specs/0002 §3：铺货时选择/推导，不落核心表）。
 *
 * <p>派生规则：
 * <ul>
 *   <li>{@code listing_id = listing-{spuId}-{channelId}}（确定性，对齐 #11 workflowId 业务键与
 *       ADR-0003 幂等语义）；</li>
 *   <li>{@code sku_set} 初始价 = master 成本价（**占位**：真正售价由 {@code price.strategy} 覆盖，
 *       保证 ListingSku.price 在 schema 要求的必填位上从第一刻起合法）；</li>
 *   <li>{@code images} 引用 SPU 资产（{@code platform_media_id} 待铺货回填）；</li>
 *   <li>{@code locales} 由调用方给定（渠道目标语言集），canonical 译文由 {@code i18n.backfill} 回填；</li>
 *   <li>{@code provenance.created_by_step = LISTING}（铺货实例本身由铺货准备产生，非 AI 产物）。</li>
 * </ul>
 */
public final class ListingDraftFactory {

    private final Clock clock;

    public ListingDraftFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock 必填");
    }

    public ProductCatalog draft(ProductCatalog master, String channelId, CategoryRef targetCategory,
                                List<String> locales) {
        if (master == null) {
            throw new IllegalArgumentException("master 文档必填");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId 必填（目标渠道账号）");
        }
        if (targetCategory == null) {
            throw new IllegalArgumentException("目标平台叶子类目必填（schema: Listing.platform_category）");
        }
        if (locales == null || locales.isEmpty()) {
            throw new IllegalArgumentException("locales 必填（提交用语言集）");
        }
        if (master.spus() == null || master.spus().size() != 1) {
            throw new IllegalArgumentException("Listing 装配需单 SPU 文档（spus() 恰 1 条）");
        }
        Spu spu = master.spus().get(0);
        String listingId = "listing-" + spu.spuId() + "-" + channelId;

        List<ListingSku> skuSet = new ArrayList<>();
        for (Sku sku : master.skus() == null ? List.<Sku>of() : master.skus()) {
            if (!Objects.equals(sku.spuId(), spu.spuId())) {
                continue;
            }
            skuSet.add(new ListingSku(sku.skuId(), sku.costPrice(), true));
        }
        if (skuSet.isEmpty()) {
            throw new IllegalArgumentException("SPU 无 SKU，无法装配 Listing: " + spu.spuId());
        }

        List<ListingImage> images = new ArrayList<>();
        for (MediaRef ref : spu.images() == null ? List.<MediaRef>of() : spu.images()) {
            images.add(new ListingImage(ref.mediaId(), ref.role(), null));
        }

        String now = Instant.now(clock).toString();
        Listing listing = new Listing(listingId, spu.spuId(), channelId,
                null, null, List.copyOf(locales),
                targetCategory, List.of(), List.of(), skuSet, images, null,
                new Provenance(ProvenanceStep.LISTING, null, spu.spuId(), now, now));

        List<Listing> listings = new ArrayList<>(
                master.listings() == null ? List.<Listing>of() : master.listings());
        listings.add(listing);
        return new ProductCatalog(master.schemaVersion(), master.spus(), master.skus(), listings,
                master.mediaAssets());
    }
}
