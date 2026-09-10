package io.autocommerce.content.listing;

import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.Provenance;
import io.autocommerce.core.catalog.model.ProvenanceStep;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 人工编辑覆盖（specs/0006 §6 / #20 AC-5）：v1 **单稿制**——AI 产物直写 Listing 字段
 * （{@code provenance.updated_by_step=AI}），人工在铺货前预览编辑就地把同一字段改成
 * {@code HUMAN}；**无候选/采纳两态、无版本表**（旧稿可溯 = Temporal Event History）。
 *
 * <p>合并语义：给出的 locale 条目覆盖同 key，其余保留（人工只改一两个语言时不丢 AI 产物）。
 * {@code degraded_steps} **保留**——人工改了标题不等于自动认领降级留痕，HITL 复核状态由人工显式处理
 * （v1 无清除入口，属后续增强）。
 */
public final class HumanContentEdit {

    private final Clock clock;

    public HumanContentEdit(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock 必填");
    }

    /**
     * 应用人工编辑并置 {@code updated_by_step=HUMAN}。
     *
     * @param titleOverrides       人工标题（locale → 文本；空则不动）
     * @param descriptionOverrides 人工描述（locale → 文本；空则不动）
     */
    public ProductCatalog apply(ProductCatalog document, String listingId,
                               Map<String, String> titleOverrides,
                               Map<String, String> descriptionOverrides) {
        Objects.requireNonNull(document, "文档必填");
        if ((titleOverrides == null || titleOverrides.isEmpty())
                && (descriptionOverrides == null || descriptionOverrides.isEmpty())) {
            throw new IllegalArgumentException("人工编辑至少需给出标题或描述的一处改动");
        }
        Listing target = (document.listings() == null ? List.<Listing>of() : document.listings()).stream()
                .filter(l -> listingId != null && listingId.equals(l.listingId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("文档内无 Listing: " + listingId));

        Map<String, String> titles = new LinkedHashMap<>(
                target.titleOverrides() == null ? Map.of() : target.titleOverrides());
        if (titleOverrides != null) {
            titles.putAll(titleOverrides);
        }
        Map<String, String> descriptions = new LinkedHashMap<>(
                target.descriptionOverrides() == null ? Map.of() : target.descriptionOverrides());
        if (descriptionOverrides != null) {
            descriptions.putAll(descriptionOverrides);
        }

        String now = Instant.now(clock).toString();
        Provenance provenance = target.provenance() == null
                ? new Provenance(ProvenanceStep.LISTING, ProvenanceStep.HUMAN, target.spuId(), now, now)
                : new Provenance(target.provenance().createdByStep(), ProvenanceStep.HUMAN,
                        target.provenance().parentRef(), target.provenance().createdAt(), now);

        Listing edited = new Listing(target.listingId(), target.spuId(), target.channelId(),
                titles.isEmpty() ? null : Map.copyOf(titles),
                descriptions.isEmpty() ? null : Map.copyOf(descriptions),
                target.locales(), target.platformCategory(), target.platformAttributes(),
                target.specMappings(), target.skuSet(), target.images(), target.degradedSteps(), provenance);

        List<Listing> listings = new ArrayList<>();
        for (Listing listing : document.listings() == null ? List.<Listing>of() : document.listings()) {
            listings.add(listing.listingId().equals(listingId) ? edited : listing);
        }
        return new ProductCatalog(document.schemaVersion(), document.spus(), document.skus(), listings,
                document.mediaAssets());
    }
}
