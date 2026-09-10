package io.autocommerce.content.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.autocommerce.core.step.AiStep;
import io.autocommerce.core.step.ChatMessage;
import io.autocommerce.core.step.ChatRequest;
import io.autocommerce.core.step.ChatRole;
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
 * <p>目标 locale **hard cap = {@value #MAX_TARGET_LOCALES}**（specs/0006 §10 fog #20 拍板）：超出硬上限的
 * locales 截断丢弃并登记 {@code degraded_steps}（"超过目标 locale 上限,截断"），避免单 Step 超
 * {@code StartToCloseTimeout=2min}。截断信号可被运营复核：未翻译的 locale 由下一轮手工补或下版本扩 cap。
 *
 * <p>读写：{@code spu.titles} / {@code spu.descriptions}（读+写，master 侧）。已有目标 locale 不重翻
 * （幂等：重跑内容链不会重复烧 token）。
 *
 * <p>失败：**不降级**——跨境无内容不可铺（specs/0006 §8 硬依赖）。Provider 失败包成
 * {@link StepExecutionException} 抛出，由计划里的 {@code critical=true} 决定内容链 failed。
 */
public final class I18nBackfillStep implements AiStep {

    public static final String ID = ContentPlan.I18N_BACKFILL;

    /** 单次翻译回填的目标 locale 硬上限（specs/0006 §10 fog #20 拍板）。 */
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
        // listing.locales 是 List<String>（ListingStepContext.read 已转），直接 cast。
        List<String> listingLocales = stringList(
                context.read(new FieldRef(ListingStepContext.LISTING_LOCALES)));
        if (!listingLocales.isEmpty()) {
            targets = listingLocales;
        }

        // Hard cap：超 MAX_TARGET_LOCALES 的 locale 截断（specs/0006 §10 fog #20）。
        // 截断信号走 DEGRADED 返回 → ContentStepExecutor.execute 统一登记 degraded_steps，
        // 不在 Step 内污染 provenance（与降级留痕共用语义）。
        List<String> effectiveTargets = targets;
        if (targets.size() > MAX_TARGET_LOCALES) {
            effectiveTargets = new ArrayList<>(targets.subList(0, MAX_TARGET_LOCALES));
        }

        Map<String, String> titles = stringMap(context.read(new FieldRef(ListingStepContext.SPU_TITLES)));
        Map<String, String> descriptions =
                stringMap(context.read(new FieldRef(ListingStepContext.SPU_DESCRIPTIONS)));
        String sourceTitle = titles.get(sourceLocale);
        if (sourceTitle == null || sourceTitle.isBlank()) {
            throw new StepExecutionException("canonical i18n 缺来源 locale 标题（" + sourceLocale
                    + "），翻译回填无输入");
        }

        Map<String, String> newTitles = new LinkedHashMap<>(titles);
        Map<String, String> newDescriptions = new LinkedHashMap<>(descriptions);
        List<String> pending = new ArrayList<>();
        for (String locale : effectiveTargets) {
            if (!locale.equals(sourceLocale) && blank(newTitles.get(locale))) {
                pending.add(locale);
            }
        }
        if (pending.isEmpty()) {
            // 全部目标 locale 已有译文（或源 locale 自身）→ 无翻译动作。
            // 但若发生过 hard cap 截断，pending 不为空也会走 try 分支，这里专门返回 OK（无产物）。
            if (targets.size() <= MAX_TARGET_LOCALES) {
                return StepResult.ok();
            }
        }

        try {
            for (String locale : pending) {
                newTitles.put(locale, translate(llm, context, systemPrompt, temperature, maxTokens,
                        sourceTitle, locale, "商品标题"));
            }
            String sourceDescription = descriptions.get(sourceLocale);
            if (sourceDescription != null && !sourceDescription.isBlank()) {
                for (String locale : pending) {
                    if (blank(newDescriptions.get(locale))) {
                        newDescriptions.put(locale, translate(llm, context, systemPrompt, temperature, maxTokens,
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
        // 发生过 hard cap 截断 → 走 DEGRADED 返回（让 ContentStepExecutor 登记 degraded_steps，
        // 但产物本身已写入 master canonical，不阻断铺货）。
        if (targets.size() > MAX_TARGET_LOCALES) {
            int truncated = targets.size() - MAX_TARGET_LOCALES;
            return StepResult.degraded("超过目标 locale 上限（" + MAX_TARGET_LOCALES + "），截断 " + truncated
                    + " 个 locale（运营可在下一轮手工补）");
        }
        return StepResult.ok();
    }

    private static String translate(LlmGateway llm, StepContext context, String systemPrompt, Double temperature,
                                    Integer maxTokens, String text, String locale, String what) {
        String user = "把下面这段" + what + "翻译成语言代码 " + locale + "：\n" + text;
        String content = llm.complete(ModelRequirement.LLM,
                        new ChatRequest(null,
                                List.of(ChatMessage.of(ChatRole.SYSTEM, systemPrompt),
                                        ChatMessage.of(ChatRole.USER, user)),
                                temperature, maxTokens),
                        context)
                .content();
        if (content == null || content.isBlank()) {
            throw new ProviderException("翻译返回空内容（locale=" + locale + "）");
        }
        return content.strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        return value == null ? Map.of() : (Map<String, String>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return value == null ? List.of() : (List<String>) value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
