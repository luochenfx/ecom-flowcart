package io.autocommerce.catalog.ingest;

import io.autocommerce.core.catalog.model.Attribute;
import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.MediaAsset;
import io.autocommerce.core.catalog.model.MediaRef;
import io.autocommerce.core.catalog.model.MediaRole;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.ProcessingState;
import io.autocommerce.core.catalog.model.Provenance;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.catalog.model.Sku;
import io.autocommerce.core.catalog.model.SkuRef;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.catalog.model.Spu;
import io.autocommerce.core.contract.dto.OfferData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 采集面 OfferData → master product 文档映射（#19）。
 *
 * <p>输入 = 平台无关采集面（{@link OfferData}，来自 OfferFetchCapability——禁环②经 core 接口
 * 吸收，本模块不感知任何 1688 字段）；输出 = ProductCatalog 文档（1 SPU + 其 SKU/MediaAsset），
 * 落 CatalogStore（schema 校验由契约测试承担，Testing §1）。
 *
 * <p>组装规则（specs/0002 §2）：
 * <ul>
 *   <li>确定性 id（重采同 offer → 同 id，幂等覆盖）：spuId = {@code spu-{platform}-{externalId}}、
 *       skuId = {@code sku-{platform}-{externalId}-{sourceSkuId}}、mediaId = {@code media-{platform}-{externalId}-{序号}}；
 *   <li>canonical i18n：来源 locale zh-CN 必填（1688 中文货源），其余 locale 由 content 链回填；
 *   <li>source_ref 一等字段照搬；来源类目（taxonomy=1688）/来源属性快照从采集面直挂，无内部类目树；
 *   <li>platform_raw = 采集面 raw（逃生口直通不丢）；
 *   <li>媒体：首图 MAIN、其余 GALLERY，processing_state=RAW（下载/处理 = 后台任务，模型只存指针）；
 *       variant 结构（LOCALIZED）v1 不生成，字段留空；
 *   <li>provenance：CAPTURE（采集原貌，未清洗/未 AI）；SPU.parent_ref = {@code offer-{externalId}}
 *       （对象级血缘：SPU→source offer，specs/0002 §7）。
 * </ul>
 *
 * <p>纯函数（无副作用）：同输入必同输出——幂等重放安全；时间戳取自 SourceRef.fetchedAt
 * （必填，缺失抛 IllegalArgumentException——provenance created_at 无合法值）。
 */
public final class OfferCatalogMapper {

    /** 1688 货源 locale（canonical i18n 的来源 locale，specs/0002 §5）。 */
    private static final String SOURCE_LOCALE = "zh-CN";

    /** 采集行为：CAPTURE（原貌），不做清洗/翻译（后续内容链以 CLEAN/AI 回填改写）。 */
    private static final ProvenanceStep CAPTURE = ProvenanceStep.CAPTURE;

    public ProductCatalog map(SourceRef ref, OfferData data) {
        Objects.requireNonNull(ref, "source_ref 必填");
        Objects.requireNonNull(data, "采集面 OfferData 必填");
        String platform = ref.platform();
        String externalId = ref.externalId();
        if (platform == null || platform.isBlank() || externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("source_ref.platform / external_id 必填（确定性 id 依据）");
        }
        String fetchedAt = ref.fetchedAt();
        if (fetchedAt == null || fetchedAt.isBlank()) {
            throw new IllegalArgumentException("source_ref.fetched_at 必填（provenance created_at）");
        }

        String spuId = spuId(platform, externalId);
        String parentRef = "offer-" + externalId;

        List<MediaAsset> mediaAssets = new ArrayList<>();
        List<MediaRef> spuImages = new ArrayList<>();
        List<String> imageUrls = data.imageUrls() == null ? List.of() : data.imageUrls();
        for (int i = 0; i < imageUrls.size(); i++) {
            MediaRole role = i == 0 ? MediaRole.MAIN : MediaRole.GALLERY;
            String mediaId = "media-" + platform + "-" + externalId + "-" + i;
            // 对象级血缘：SPU → source offer；SKU/MediaAsset 父对象由自身所属关系隐含，不重复挂 parent_ref
            mediaAssets.add(new MediaAsset(
                    mediaId, imageUrls.get(i), null, role, ProcessingState.RAW,
                    null, null, null,
                    captureProvenance(null, fetchedAt)));
            spuImages.add(new MediaRef(mediaId, role));
        }

        List<Sku> skus = new ArrayList<>();
        List<SkuRef> spuSkus = new ArrayList<>();
        List<OfferData.OfferSku> offerSkus = data.skus() == null ? List.of() : data.skus();
        for (OfferData.OfferSku offerSku : offerSkus) {
            String sourceSkuId = offerSku.sourceSkuId();
            if (offerSku.price() == null) {
                throw new IllegalArgumentException(
                        "SKU 缺 cost_price（1688 skuMap 无 price? sourceSkuId=" + sourceSkuId + "）");
            }
            String skuId = skuId(platform, externalId, sourceSkuId);
            skus.add(new Sku(
                    skuId, spuId,
                    offerSku.specs() == null ? List.of() : offerSku.specs(),
                    offerSku.price(), null, null, sourceSkuId,
                    captureProvenance(null, fetchedAt)));
            spuSkus.add(new SkuRef(skuId));
        }

        Spu spu = new Spu(
                spuId,
                ref,
                Map.of(SOURCE_LOCALE, data.title() == null ? "" : data.title()),
                null,
                spuImages,
                spuSkus,
                normalize(data.sourceCategories()),
                normalizeAttrs(data.attributes()),
                data.raw(),
                captureProvenance(parentRef, fetchedAt));

        return new ProductCatalog("0.1.0",
                List.of(spu), List.copyOf(skus), List.of(), List.copyOf(mediaAssets));
    }

    private static Provenance captureProvenance(String parentRef, String fetchedAt) {
        return new Provenance(CAPTURE, null, parentRef, fetchedAt, fetchedAt);
    }

    private static List<CategoryRef> normalize(List<CategoryRef> categories) {
        return categories == null ? List.of() : categories;
    }

    private static List<Attribute> normalizeAttrs(List<Attribute> attributes) {
        return attributes == null ? List.of() : attributes;
    }

    /** 确定性 id 规则（同 offer 重采幂等；跨平台不冲突——platform 前缀）。 */
    private static String spuId(String platform, String externalId) {
        return "spu-" + platform + "-" + externalId;
    }

    private static String skuId(String platform, String externalId, String sourceSkuId) {
        return "sku-" + platform + "-" + externalId + "-" + sourceSkuId;
    }
}
