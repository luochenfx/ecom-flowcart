package io.autocommerce.content.model;

import io.autocommerce.core.catalog.model.DegradedStep;
import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.ListingSku;
import io.autocommerce.core.catalog.model.MediaAsset;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.Provenance;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.catalog.model.Sku;
import io.autocommerce.core.catalog.model.Spu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 内容链工作集（#20）：一份 master 文档 + 目标 Listing 的**可变**读改写视图。
 *
 * <p>标准模型是不可变 record，而 Step 契约要求"读标准模型字段 → 写回标准模型字段"。本类就是
 * 这一读改写的落点：Step 只经 {@link io.autocommerce.core.step.StepContext}（由
 * {@link io.autocommerce.content.step.ListingStepContext} 实现）触碰字段，不直接持有 record；
 * 链末 {@link #toDocument(String)} 把改动物化回 ProductCatalog 文档（落库形态，schema 契约不变）。
 *
 * <p>血缘（specs/0006 §6 单稿制）：被 Step 写过的对象在物化时把 provenance 置
 * {@code updated_by_step=AI}（created_by_step / created_at 保留）；未被触碰的对象原样透传——
 * 无改动即无新稿，不做无谓的 provenance 漂移。
 *
 * <p>降级留痕（specs/0006 §5）：非硬依赖 Step 失败 → {@link #addDegradedStep(String, String)}，
 * 物化时写入 {@code listing.degraded_steps}（看板 HITL），内容链继续。
 */
public final class ContentWorkingSet {

    private final ProductCatalog source;
    private final String listingId;
    private final Spu spu;
    private final Listing listing;
    private final List<Sku> masterSkus;

    private final Map<String, String> spuTitles;
    private final Map<String, String> spuDescriptions;
    private final Map<String, String> titleOverrides;
    private final Map<String, String> descriptionOverrides;
    private final Map<String, MediaAsset> mediaById;
    private final List<DegradedStep> degradedSteps;

    private List<ListingSku> skuSet;
    private boolean spuTouched;
    /**
     * AI 真实写过 listing 字段（overrides / sku_set 等）。物化时把 provenance 升为 AI。
     * 区别于 {@link #degradedTouched}：后者仅登记降级留痕——降级产物不算 AI 写的，不该升 provenance。
     */
    private boolean listingAiTouched;
    /**
     * 本次运行仅动了降级留痕（degraded_steps）。物化时仍写 listing（落库 degraded_steps），
     * 但 provenance.updated_by_step 保持上游值，不标 AI。
     */
    private boolean degradedTouched;
    private final Set<String> mediaTouched = new LinkedHashSet<>();

    private ContentWorkingSet(ProductCatalog source, String listingId, Spu spu, Listing listing,
                              List<Sku> masterSkus, Map<String, String> spuTitles,
                              Map<String, String> spuDescriptions, Map<String, String> titleOverrides,
                              Map<String, String> descriptionOverrides, Map<String, MediaAsset> mediaById,
                              List<DegradedStep> degradedSteps, List<ListingSku> skuSet) {
        this.source = source;
        this.listingId = listingId;
        this.spu = spu;
        this.listing = listing;
        this.masterSkus = masterSkus;
        this.spuTitles = spuTitles;
        this.spuDescriptions = spuDescriptions;
        this.titleOverrides = titleOverrides;
        this.descriptionOverrides = descriptionOverrides;
        this.mediaById = mediaById;
        this.degradedSteps = degradedSteps;
        this.skuSet = skuSet;
    }

    /**
     * 以 master 文档 + 目标 Listing 打开工作集。
     *
     * @throws IllegalArgumentException 文档里没有该 listingId、或 Listing 指向的 SPU 不在文档内
     */
    public static ContentWorkingSet of(ProductCatalog document, String listingId) {
        Objects.requireNonNull(document, "master 文档必填");
        Listing listing = (document.listings() == null ? List.<Listing>of() : document.listings()).stream()
                .filter(l -> listingId != null && listingId.equals(l.listingId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("文档内无 Listing: " + listingId));
        Spu spu = (document.spus() == null ? List.<Spu>of() : document.spus()).stream()
                .filter(s -> Objects.equals(s.spuId(), listing.spuId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Listing " + listingId + " 指向的 SPU 不在文档内: " + listing.spuId()));

        Map<String, MediaAsset> media = new LinkedHashMap<>();
        for (MediaAsset asset : document.mediaAssets() == null ? List.<MediaAsset>of() : document.mediaAssets()) {
            media.put(asset.mediaId(), asset);
        }
        List<Sku> masterSkus = (document.skus() == null ? List.<Sku>of() : document.skus()).stream()
                .filter(s -> Objects.equals(s.spuId(), spu.spuId()))
                .toList();

        return new ContentWorkingSet(
                document, listingId, spu, listing, masterSkus,
                new LinkedHashMap<>(spu.titles() == null ? Map.of() : spu.titles()),
                new LinkedHashMap<>(spu.descriptions() == null ? Map.of() : spu.descriptions()),
                new LinkedHashMap<>(listing.titleOverrides() == null ? Map.of() : listing.titleOverrides()),
                new LinkedHashMap<>(
                        listing.descriptionOverrides() == null ? Map.of() : listing.descriptionOverrides()),
                media,
                new ArrayList<>(listing.degradedSteps() == null ? List.of() : listing.degradedSteps()),
                new ArrayList<>(listing.skuSet() == null ? List.of() : listing.skuSet()));
    }

    // ---- 读（Step 经 StepContext 访问） ----

    public String listingId() {
        return listingId;
    }

    public Spu spu() {
        return spu;
    }

    public Listing listing() {
        return listing;
    }

    /** master SKU（含 cost_price）——价格策略 Step 的输入。 */
    public List<Sku> masterSkus() {
        return masterSkus;
    }

    public Map<String, String> spuTitles() {
        return spuTitles;
    }

    public Map<String, String> spuDescriptions() {
        return spuDescriptions;
    }

    public Map<String, String> titleOverrides() {
        return titleOverrides;
    }

    public Map<String, String> descriptionOverrides() {
        return descriptionOverrides;
    }

    public List<String> locales() {
        return listing.locales() == null ? List.of() : listing.locales();
    }

    public List<ListingSku> skuSet() {
        return skuSet;
    }

    public void skuSet(List<ListingSku> value) {
        this.skuSet = new ArrayList<>(value == null ? List.of() : value);
        this.listingAiTouched = true;
    }

    public List<MediaAsset> mediaAssets() {
        return List.copyOf(mediaById.values());
    }

    /** 整体替换媒体资产集合（媒体 Step 的写回口）；新增/替换的资产记为已触碰。 */
    public void mediaAssets(List<MediaAsset> value) {
        for (MediaAsset asset : value == null ? List.<MediaAsset>of() : value) {
            mediaById.put(asset.mediaId(), asset);
            mediaTouched.add(asset.mediaId());
        }
    }

    public List<DegradedStep> degradedSteps() {
        return List.copyOf(degradedSteps);
    }

    // ---- 写标记 ----

    public void markSpuTouched() {
        this.spuTouched = true;
    }

    public void markListingTouched() {
        this.listingAiTouched = true;
    }

    /**
     * 登记一条降级留痕。**本次运行的判定覆盖该 Step 的历史留痕**（同 step 重跑以最新 reason 为准）：
     * 留痕表达的是"这份 Listing 当前的内容缺口"，不是"历史上出过几次问题"——不累积成流水账。
     *
     * <p>降级产物 = 缺省值（原文/成本价），**不算 AI 写的**——provenance 保持上游值，不升 AI
     * （specs/0006 §6 单稿制：物化只对真实写入盖章）。
     */
    public void addDegradedStep(String step, String reason) {
        degradedSteps.removeIf(d -> Objects.equals(d.step(), step));
        degradedSteps.add(new DegradedStep(step, reason));
        degradedTouched = true;
    }

    /**
     * 清除某 Step 的降级留痕（本次运行该 Step 成功 → 历史留痕失效，看板不该再亮 HITL）。
     *
     * <p>只对**本次计划内执行过的** Step 调用：不在计划内的 Step 无法判定其缺口是否已补，
     * 保守保留留痕（重跑该 Step 才会消除）。
     */
    public void clearDegradedStep(String step) {
        if (degradedSteps.removeIf(d -> Objects.equals(d.step(), step))) {
            degradedTouched = true;
        }
    }

    // ---- 物化 ----

    /**
     * 物化回 ProductCatalog 文档。
     *
     * @param updatedAtIso 本次内容链的时间戳（ISO-8601；由编排层注入时钟，保证可测）
     */
    public ProductCatalog toDocument(String updatedAtIso) {
        Spu newSpu = spu;
        if (spuTouched) {
            newSpu = new Spu(spu.spuId(), spu.sourceRef(), Map.copyOf(spuTitles),
                    spuDescriptions.isEmpty() ? spu.descriptions() : Map.copyOf(spuDescriptions),
                    spu.images(), spu.skus(), spu.sourceCategories(), spu.attributes(), spu.platformRaw(),
                    touched(spu.provenance(), updatedAtIso));
        }

        Listing newListing = listing;
        if (listingAiTouched || degradedTouched) {
            newListing = new Listing(listing.listingId(), listing.spuId(), listing.channelId(),
                    titleOverrides.isEmpty() ? listing.titleOverrides() : Map.copyOf(titleOverrides),
                    descriptionOverrides.isEmpty() ? listing.descriptionOverrides()
                            : Map.copyOf(descriptionOverrides),
                    listing.locales(), listing.platformCategory(), listing.platformAttributes(),
                    listing.specMappings(), List.copyOf(skuSet),
                    listing.images(), List.copyOf(degradedSteps),
                    listingAiTouched ? touched(listing.provenance(), updatedAtIso) : listing.provenance());
        }

        List<MediaAsset> newMedia = new ArrayList<>();
        for (MediaAsset asset : mediaById.values()) {
            if (mediaTouched.contains(asset.mediaId()) && asset.provenance() != null
                    && asset.provenance().updatedByStep() != ProvenanceStep.AI) {
                newMedia.add(new MediaAsset(asset.mediaId(), asset.sourceUrl(), asset.storageRef(),
                        asset.role(), asset.processingState(), asset.variantOf(), asset.variantPurpose(),
                        asset.variantLocale(), touched(asset.provenance(), updatedAtIso)));
            } else {
                newMedia.add(asset);
            }
        }

        List<Spu> spus = new ArrayList<>();
        for (Spu s : source.spus() == null ? List.<Spu>of() : source.spus()) {
            spus.add(Objects.equals(s.spuId(), spu.spuId()) ? newSpu : s);
        }

        List<Listing> listings = new ArrayList<>();
        for (Listing l : source.listings() == null ? List.<Listing>of() : source.listings()) {
            listings.add(l.listingId().equals(listingId) ? newListing : l);
        }
        return new ProductCatalog(source.schemaVersion(), spus, source.skus(), listings, newMedia);
    }

    private static Provenance touched(Provenance provenance, String updatedAtIso) {
        if (provenance == null) {
            return new Provenance(ProvenanceStep.AI, null, null, updatedAtIso, updatedAtIso);
        }
        return new Provenance(provenance.createdByStep(), ProvenanceStep.AI, provenance.parentRef(),
                provenance.createdAt(), updatedAtIso);
    }
}
