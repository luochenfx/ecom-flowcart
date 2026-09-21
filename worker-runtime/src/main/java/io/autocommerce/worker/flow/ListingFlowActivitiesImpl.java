package io.autocommerce.worker.flow;

import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.content.listing.ListingDraftFactory;
import io.autocommerce.core.catalog.model.DegradedStep;
import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.temporal.failure.ApplicationFailure;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * 编排链 activity 实现（specs/0007 §4.4）：装配 Listing / 内容就绪断言，两类副作用。
 *
 * <p><b>业务逻辑不在此处</b>：装配委托 content 模块的 {@link ListingDraftFactory}（此前全仓零调用点，
 * 本 activity 是它的首个调用点），就绪判定只是回读已落库文档的 {@code degraded_steps}——本类只做
 * 「读 store → 改 / 判 → 落库」的闭合。
 *
 * <p><b>内容就绪是铺货硬前置</b>（specs/0007 §5）：{@code degraded_steps} 非空 ⇒ 抛不可重试失败，
 * 使父 workflow failed、<b>不启动铺货链</b>。这是**编排层**的新决策——content 模块语义不变（内容链
 * 自身 degraded 仍算 completed），本类只把它转化为一个明确的失败信号（CONTEXT.md「内容就绪」）。
 */
public final class ListingFlowActivitiesImpl implements ListingFlowActivities {

    /** 内容未就绪（degraded_steps 非空）时的 ApplicationFailure 类型——父链据此失败。 */
    public static final String CONTENT_NOT_READY = "CONTENT_NOT_READY";

    private final CatalogStore store;
    private final Clock clock;

    /**
     * @param store catalog 文档库（v1 = {@code JsonFileCatalogStore}；换真库不影响本类）
     * @param clock 装配 Listing 的时间来源（{@link ListingDraftFactory} 用）
     */
    public ListingFlowActivitiesImpl(CatalogStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "CatalogStore 必填");
        this.clock = Objects.requireNonNull(clock, "Clock 必填");
    }

    @Override
    public Listing assembleListing(AssembleListingInput input) {
        ProductCatalog master = load(input.spuId());
        ProductCatalog drafted = new ListingDraftFactory(clock).draft(
                master, input.channelId(), input.targetCategory(), input.locales());
        store.put(drafted);
        // 内容链读回的目标 Listing：从刚落库的文档定位（listingId 由工厂确定性派生）
        return listing(drafted, listingIdOf(input.spuId(), input.channelId()));
    }

    @Override
    public Listing assertContentReady(String spuId, String listingId) {
        Listing listing = listing(load(spuId), listingId);
        List<DegradedStep> degraded =
                listing.degradedSteps() == null ? List.of() : listing.degradedSteps();
        if (!degraded.isEmpty()) {
            List<String> ids = degraded.stream().map(DegradedStep::step).toList();
            throw ApplicationFailure.newNonRetryableFailure(
                    "内容未就绪：degraded_steps 非空 " + ids
                            + "（硬前置：降级 Listing 不许铺货，specs/0007 §5）",
                    CONTENT_NOT_READY);
        }
        return listing;
    }

    private ProductCatalog load(String spuId) {
        return store.get(spuId).orElseThrow(() -> new IllegalStateException(
                "CatalogStore 无此 SPU 文档: " + spuId
                        + "（编排链的前置 = 采集已落库）"));
    }

    private static Listing listing(ProductCatalog doc, String listingId) {
        List<Listing> listings = doc.listings() == null ? List.of() : doc.listings();
        return listings.stream()
                .filter(l -> listingId.equals(l.listingId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "文档内无目标 Listing: " + listingId + "（装配未落库？）"));
    }

    /** 与内容链 / 铺货链同口径的 Listing id（{@code listing-{spuId}-{channelId}}）。 */
    private static String listingIdOf(String spuId, String channelId) {
        return "listing-" + spuId + "-" + channelId;
    }
}
