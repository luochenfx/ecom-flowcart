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
 * {@code desc.generate}（specs/0006 §8）：生成平台描述改写稿 → {@code listing.description_overrides}。
 *
 * <p>输入取 canonical 描述（不同 locale 各取该 locale）；canonical 无描述时退化以标题为素材生成
 * （#19 采集只保证有标题，描述常缺）。**生成**而非翻译——翻译由 {@code i18n.backfill} 负责。
 *
 * <p>失败 = 降级（保留原文 = 不写 overrides），登记 {@code degraded_steps}，内容链继续。
 */
public final class DescGenerateStep implements AiStep {

    public static final String ID = ContentPlan.DESC_GENERATE;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是电商详情页文案。根据给定的商品标题（或已有描述）写一段该平台风格的卖点描述："
                    + "分点陈述核心卖点、材质、适用场景，120-200 字。只输出描述本身，不加解释。";

    private final LlmGateway llm;

    public DescGenerateStep(LlmGateway llm) {
        this.llm = llm;
    }

    @Override
    public StepDescriptor descriptor() {
        return new StepDescriptor(
                ID,
                List.of(new FieldRef(ListingStepContext.SPU_DESCRIPTIONS),
                        new FieldRef(ListingStepContext.SPU_TITLES),
                        new FieldRef(ListingStepContext.LISTING_LOCALES)),
                List.of(new FieldRef(ListingStepContext.LISTING_DESCRIPTION_OVERRIDES)),
                ModelRequirement.LLM,
                defaultParams());
    }

    static JsonNode defaultParams() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("temperature", 0.7);
        params.put("max_tokens", 1024);
        params.put("system_prompt", DEFAULT_SYSTEM_PROMPT);
        return params;
    }

    @Override
    public StepResult execute(StepContext context) throws StepExecutionException {
        Map<String, String> titles =
                StepValues.stringMap(context, new FieldRef(ListingStepContext.SPU_TITLES));
        Map<String, String> descriptions =
                StepValues.stringMap(context, new FieldRef(ListingStepContext.SPU_DESCRIPTIONS));
        List<String> locales =
                StepValues.stringList(context, new FieldRef(ListingStepContext.LISTING_LOCALES));

        List<String> usable = new ArrayList<>();
        for (String locale : locales) {
            if (StepValues.nonBlank(descriptions.get(locale)) || StepValues.nonBlank(titles.get(locale))) {
                usable.add(locale);
            }
        }
        if (usable.isEmpty()) {
            return StepResult.degraded("canonical 无可用描述/标题素材（locales=" + locales + "），保留原文");
        }

        JsonNode params = context.params();
        Double temperature = StepParams.number(params, "temperature", 0.7);
        Integer maxTokens = StepParams.integer(params, "max_tokens", 1024);
        String systemPrompt = StepParams.text(params, "system_prompt", DEFAULT_SYSTEM_PROMPT);

        Map<String, String> overrides = new LinkedHashMap<>();
        try {
            for (String locale : usable) {
                String material = StepValues.nonBlank(descriptions.get(locale))
                        ? descriptions.get(locale)
                        : titles.get(locale);
                String user = "语言代码：" + locale + "\n素材：" + material;
                String generated = StepLlm.complete(llm, context, systemPrompt, user, temperature, maxTokens);
                if (StepValues.nonBlank(generated)) {
                    overrides.put(locale, generated.strip());
                }
            }
        } catch (ProviderException e) {
            return StepResult.degraded("描述生成失败：" + e.getMessage() + "（保留原文）");
        }
        if (overrides.isEmpty()) {
            return StepResult.degraded("描述生成无有效产物，保留原文");
        }
        context.write(new FieldRef(ListingStepContext.LISTING_DESCRIPTION_OVERRIDES), overrides);
        return StepResult.ok();
    }
}
