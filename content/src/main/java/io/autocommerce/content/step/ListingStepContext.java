package io.autocommerce.content.step;

import com.fasterxml.jackson.databind.JsonNode;
import io.autocommerce.core.catalog.model.ListingSku;
import io.autocommerce.core.catalog.model.MediaAsset;
import io.autocommerce.core.step.ChatUsage;
import io.autocommerce.core.step.FieldRef;
import io.autocommerce.core.step.StepContext;
import io.autocommerce.content.model.ContentWorkingSet;
import io.autocommerce.content.model.ModelCall;
import io.autocommerce.content.model.UsageRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * StepContext 的 content 实现（specs/0006 §3）：把 Step 的字段级读写落到
 * {@link ContentWorkingSet}（标准模型的可变视图）。
 *
 * <p><b>字段路径词汇表（v1）</b>——刻意收敛为显式白名单，而非反射式 JSON path：
 * 契约面越小越可审计，越界路径立即失败（不静默返回 null）。
 *
 * <table>
 *   <caption>FieldRef 路径</caption>
 *   <tr><td>{@code spu.titles}</td><td>master canonical 标题 Map&lt;locale,string&gt;（读写）</td></tr>
 *   <tr><td>{@code spu.descriptions}</td><td>master canonical 描述 Map&lt;locale,string&gt;（读写）</td></tr>
 *   <tr><td>{@code spu.skus}</td><td>master SKU 列表（含 cost_price；只读）</td></tr>
 *   <tr><td>{@code listing.title_overrides}</td><td>平台改写标题 Map&lt;locale,string&gt;（读写）</td></tr>
 *   <tr><td>{@code listing.description_overrides}</td><td>平台改写描述 Map&lt;locale,string&gt;（读写）</td></tr>
 *   <tr><td>{@code listing.locales}</td><td>提交用 locale 集（只读）</td></tr>
 *   <tr><td>{@code listing.sku_set}</td><td>Listing SKU 售价集（读写）</td></tr>
 *   <tr><td>{@code media}</td><td>媒体资产列表（读写）</td></tr>
 * </table>
 *
 * <p>读返回不可变快照、写才登记触碰（provenance 只在真有写入时漂移到 AI，specs/0006 §6）。
 * 同时实现 {@link UsageRecorder}：LLM 网关把每次调用的 token 用量记到本上下文，由内容链汇总。
 */
public final class ListingStepContext implements StepContext, UsageRecorder {

    public static final String SPU_TITLES = "spu.titles";
    public static final String SPU_DESCRIPTIONS = "spu.descriptions";
    public static final String SPU_SKUS = "spu.skus";
    public static final String LISTING_TITLE_OVERRIDES = "listing.title_overrides";
    public static final String LISTING_DESCRIPTION_OVERRIDES = "listing.description_overrides";
    public static final String LISTING_LOCALES = "listing.locales";
    public static final String LISTING_SKU_SET = "listing.sku_set";
    public static final String MEDIA = "media";

    private final ContentWorkingSet working;
    private final JsonNode params;
    private final List<ModelCall> modelCalls = new ArrayList<>();

    public ListingStepContext(ContentWorkingSet working, JsonNode params) {
        this.working = working;
        this.params = params;
    }

    @Override
    public JsonNode params() {
        return params;
    }

    @Override
    public Object read(FieldRef field) {
        return switch (path(field)) {
            case SPU_TITLES -> Map.copyOf(working.spuTitles());
            case SPU_DESCRIPTIONS -> Map.copyOf(working.spuDescriptions());
            case SPU_SKUS -> List.copyOf(working.masterSkus());
            case LISTING_TITLE_OVERRIDES -> Map.copyOf(working.titleOverrides());
            case LISTING_DESCRIPTION_OVERRIDES -> Map.copyOf(working.descriptionOverrides());
            case LISTING_LOCALES -> List.copyOf(working.locales());
            case LISTING_SKU_SET -> List.copyOf(working.skuSet());
            case MEDIA -> working.mediaAssets();
            default -> throw new IllegalArgumentException("StepContext 不认识的字段路径: " + path(field));
        };
    }

    @Override
    public void write(FieldRef field, Object value) {
        switch (path(field)) {
            case SPU_TITLES -> {
                working.spuTitles().clear();
                working.spuTitles().putAll(stringMap(value, SPU_TITLES));
                working.markSpuTouched();
            }
            case SPU_DESCRIPTIONS -> {
                working.spuDescriptions().clear();
                working.spuDescriptions().putAll(stringMap(value, SPU_DESCRIPTIONS));
                working.markSpuTouched();
            }
            case LISTING_TITLE_OVERRIDES -> {
                working.titleOverrides().clear();
                working.titleOverrides().putAll(stringMap(value, LISTING_TITLE_OVERRIDES));
                working.markListingTouched();
            }
            case LISTING_DESCRIPTION_OVERRIDES -> {
                working.descriptionOverrides().clear();
                working.descriptionOverrides().putAll(stringMap(value, LISTING_DESCRIPTION_OVERRIDES));
                working.markListingTouched();
            }
            case LISTING_SKU_SET -> working.skuSet(list(value, ListingSku.class, LISTING_SKU_SET));
            case MEDIA -> working.mediaAssets(list(value, MediaAsset.class, MEDIA));
            default -> throw new IllegalArgumentException("StepContext 不认识的字段路径: " + path(field));
        }
    }

    @Override
    public void recordUsage(String model, ChatUsage usage) {
        modelCalls.add(new ModelCall(model, usage));
    }

    /** 本 Step 执行期间的模型调用明细（内容链汇总进执行记录）。 */
    public List<ModelCall> modelCalls() {
        return List.copyOf(modelCalls);
    }

    private static String path(FieldRef field) {
        if (field == null || field.path() == null || field.path().isBlank()) {
            throw new IllegalArgumentException("FieldRef.path 必填");
        }
        return field.path();
    }

    private static Map<String, String> stringMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(path + " 需要 Map<String,String>，实得 "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String text)) {
                throw new IllegalArgumentException(path + " 的键值必须是 String");
            }
            result.put(key, text);
        }
        return result;
    }

    private static <T> List<T> list(Object value, Class<T> type, String path) {
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException(path + " 需要 List<" + type.getSimpleName() + ">，实得 "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        List<T> result = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (!type.isInstance(element)) {
                throw new IllegalArgumentException(path + " 元素类型须为 " + type.getSimpleName() + "，实得 "
                        + (element == null ? "null" : element.getClass().getName()));
            }
            result.add(type.cast(element));
        }
        return result;
    }
}
