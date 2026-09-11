package io.autocommerce.content.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.autocommerce.core.step.AiStep;
import io.autocommerce.core.step.FieldRef;
import io.autocommerce.core.step.ModelRequirement;
import io.autocommerce.core.step.ProviderException;
import io.autocommerce.core.step.StepContext;
import io.autocommerce.core.step.StepDescriptor;
import io.autocommerce.core.step.StepExecutionException;
import io.autocommerce.core.step.StepResult;
import io.autocommerce.content.ContentPlan;
import io.autocommerce.content.step.ListingStepContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code i18n.backfill}（specs/0006 §8）：翻译回填 **master canonical i18n**——一处翻译多处复用
 * （跨境一次铺货要提交 en/ru/es 多份，逐 Listing 翻译会重复烧钱，specs/0002 §6）。
 *
 * <p>目标 locales 优先级（{@code #20 AC-3}）：
 * <ol>
 *   <li>Listing 装配时声明的 {@code listing.locales}（一处翻译多处复用的真实形态）——优先；</li>
 *   <li>{@code params.target_locales}（默认 = {@code ["en"]}，保留 current behavior 兼容已有测试）。</li>
 * </ol>
 *
 * <p>目标 locale **hard cap = {@value #MAX_TARGET_LOCALES}**（specs/0006 §10 回填）：上限来自单 Step 的
 * {@code StartToCloseTimeout=2min}——10 个目标 locale × (标题 + 描述) = 20 次串行调用是该档超时的
 * 上限估计。超出上限的 locale 本轮不翻译。
 *
 * <p>读写：{@code spu.titles} / {@code spu.descriptions}（读+写，master 侧）。已有目标 locale 不重翻
 * （幂等：重跑内容链不会重复烧 token）。
 *
 * <p><b>失败：硬依赖，但 Step 不自行判定"致命与否"</b>（specs/0006 §5）。两条出口都只表达事实：
 * <ul>
 *   <li>Provider 失败 / 缺来源 locale 标题 → 抛 {@link StepExecutionException}；</li>
 *   <li>目标 locale 超 hard cap 被截断 → 返回 {@link StepResult#degraded StepResult.degraded}（"产物不完整"）。</li>
 * </ul>
 * 是否致命由计划里的 {@code critical} 位决定：{@code i18n.backfill} 在标准计划里 {@code critical=true}，
 * 故**两种出口都收敛为内容链 failed**（#41 review 拍板："硬依赖的失败"是全称——截断 = 某些 locale
 * 无内容 = §8"跨境无内容不可铺"的同一性质；见 specs/0006 §10 决议与反悔路径）。
 */
public final class I18nBackfillStep implements AiStep {

    public static final String ID = ContentPlan.I18N_BACKFILL;

    /** 单轮翻译回填的目标 locale 硬上限（specs/0006 §10 回填）。 */
    static final int MAX_TARGET_LOCALES = 10;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是电商本地化翻译。只输出译文本身，不加解释、不加引号、不保留原文。保留品牌名与型号。";

    private final LlmGateway llm;

    public I18nBackfillStep(LlmGateway llm) {
        this.llm = llm;
    }

    @Override
    public StepDescriptor descriptor() {
        return new StepDescriptor(
                ID,
                List.of(new FieldRef(ListingStepContext.SPU_TITLES),
                        new FieldRef(ListingStepContext.SPU_DESCRIPTIONS),
                        new FieldRef(ListingStepContext.LISTING_LOCALES)),
                List.of(new FieldRef(ListingStepContext.SPU_TITLES),
                        new FieldRef(ListingStepContext.SPU_DESCRIPTIONS)),
                ModelRequirement.LLM,
                defaultParams());
    }

    static JsonNode defaultParams() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("source_locale", "zh-CN");
        params.putArray("target_locales").add("en");
        params.put("temperature", 0.2);
        params.put("max_tokens", 512);
        params.put("system_prompt", DEFAULT_SYSTEM_PROMPT);
        return params;
    }

    @Override
    public StepResult execute(StepContext context) throws StepExecutionException {
        JsonNode params = context.params();
        String sourceLocale = StepParams.text(params, "source_locale", "zh-CN");
        List<String> targets = StepParams.stringList(params, "target_locales");
        Double temperature = StepParams.number(params, "temperature", 0.2);
        Integer maxTokens = StepParams.integer(params, "max_tokens", 512);
        String systemPrompt = StepParams.text(params, "system_prompt", DEFAULT_SYSTEM_PROMPT);

        // AC-3：Listing 实际目标 locales 优先于 params.target_locales（specs/0006 §2 一处翻译多处复用）。
        // 留兜底：params 缺省 ["en"] 保留 current behavior，零 locale 也不致命。
        List<String> listingLocales =
                StepValues.stringList(context.read(new FieldRef(ListingStepContext.LISTING_LOCALES)));
        if (!listingLocales.isEmpty()) {
            targets = listingLocales;
        }

        // Hard cap：超 MAX_TARGET_LOCALES 的 locale 截断（specs/0006 §10）。
        List<String> effectiveTargets = targets;
        if (targets.size() > MAX_TARGET_LOCALES) {
            effectiveTargets = new ArrayList<>(targets.subList(0, MAX_TARGET_LOCALES));
        }

        Map<String, String> titles = StepValues.stringMap(context.read(new FieldRef(ListingStepContext.SPU_TITLES)));
        Map<String, String> descriptions =
                StepValues.stringMap(context.read(new FieldRef(ListingStepContext.SPU_DESCRIPTIONS)));
        String sourceTitle = titles.get(sourceLocale);
        if (StepValues.blank(sourceTitle)) {
            throw new StepExecutionException("canonical i18n 缺来源 locale 标题（" + sourceLocale
                    + "），翻译回填无输入");
        }

        Map<String, String> newTitles = new LinkedHashMap<>(titles);
        Map<String, String> newDescriptions = new LinkedHashMap<>(descriptions);
        List<String> pending = new ArrayList<>();
        for (String locale : effectiveTargets) {
            if (!locale.equals(sourceLocale) && StepValues.blank(newTitles.get(locale))) {
                pending.add(locale);
            }
        }
        if (pending.isEmpty() && targets.size() <= MAX_TARGET_LOCALES) {
            // 全部目标 locale 已有译文（或源 locale 自身）→ 无翻译动作。
            // 注意：pending 空**但发生过截断**时不走这里——截断本身要上报（见下方 DEGRADED），
            // 否则"有一部分 locale 本轮没翻"这个缺口会静默消失。
            return StepResult.ok();
        }

        try {
            for (String locale : pending) {
                newTitles.put(locale, translate(context, systemPrompt, temperature, maxTokens,
                        sourceTitle, locale, "商品标题"));
            }
            String sourceDescription = descriptions.get(sourceLocale);
            if (StepValues.nonBlank(sourceDescription)) {
                for (String locale : pending) {
                    if (StepValues.blank(newDescriptions.get(locale))) {
                        newDescriptions.put(locale, translate(context, systemPrompt, temperature, maxTokens,
                                sourceDescription, locale, "商品描述"));
                    }
                }
            }
        } catch (ProviderException e) {
            throw new StepExecutionException("翻译回填失败（硬依赖：跨境无内容不可铺）: " + e.getMessage(), e);
        }

        context.write(new FieldRef(ListingStepContext.SPU_TITLES), newTitles);
        if (!newDescriptions.isEmpty()) {
            context.write(new FieldRef(ListingStepContext.SPU_DESCRIPTIONS), newDescriptions);
        }
        if (targets.size() > MAX_TARGET_LOCALES) {
            int truncated = targets.size() - MAX_TARGET_LOCALES;
            return StepResult.degraded("超过目标 locale 上限（" + MAX_TARGET_LOCALES + "），截断 " + truncated
                    + " 个 locale（运营可在下一轮手工补）");
        }
        return StepResult.ok();
    }

    /** 译文非空即视为一次成功翻译；空返回视为 provider 未交付内容（硬依赖下不可放行）。 */
    private String translate(StepContext context, String systemPrompt, Double temperature,
                             Integer maxTokens, String text, String locale, String what) {
        String user = "把下面这段" + what + "翻译成语言代码 " + locale + "：\n" + text;
        String content = StepLlm.complete(llm, context, systemPrompt, user, temperature, maxTokens);
        if (StepValues.blank(content)) {
            throw new ProviderException("翻译返回空内容（locale=" + locale + "）");
        }
        return content.strip();
    }
}
