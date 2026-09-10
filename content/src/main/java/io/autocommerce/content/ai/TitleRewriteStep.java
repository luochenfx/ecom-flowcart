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
 * {@code title.rewrite}（specs/0006 §8）：按平台风格改写标题 → **Listing 改写稿**
 * （{@code listing.title_overrides}，按 locale 覆盖 canonical）。读 canonical（翻译回填后的
 * {@code spu.titles}）而非直读来源标题——翻译与改写是两件事（specs/0002 §6）。
 *
 * <p>失败 = 降级（specs/0006 §8"用清洗后原文 + degraded"）：不写 overrides，铺货时自然回退
 * canonical 标题；登记 {@code degraded_steps}，内容链继续。
 */
public final class TitleRewriteStep implements AiStep {

    public static final String ID = ContentPlan.TITLE_REWRITE;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是电商平台标题运营。把给定标题改写为该平台风格的卖点标题：突出核心卖点、"
                    + "不堆砌无关词、不超过 60 字。只输出标题本身，不加解释、不加引号。";

    private final LlmGateway llm;

    public TitleRewriteStep(LlmGateway llm) {
        this.llm = llm;
    }

    @Override
    public StepDescriptor descriptor() {
        return new StepDescriptor(
                ID,
                List.of(new FieldRef(ListingStepContext.SPU_TITLES),
                        new FieldRef(ListingStepContext.LISTING_LOCALES)),
                List.of(new FieldRef(ListingStepContext.LISTING_TITLE_OVERRIDES)),
                ModelRequirement.LLM,
                defaultParams());
    }

    static JsonNode defaultParams() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("temperature", 0.7);
        params.put("max_tokens", 256);
        params.put("system_prompt", DEFAULT_SYSTEM_PROMPT);
        return params;
    }

    @Override
    public StepResult execute(StepContext context) throws StepExecutionException {
        Map<String, String> titles = stringMap(context.read(new FieldRef(ListingStepContext.SPU_TITLES)));
        List<String> locales = stringList(context.read(new FieldRef(ListingStepContext.LISTING_LOCALES)));

        List<String> usable = new ArrayList<>();
        for (String locale : locales) {
            String canonical = titles.get(locale);
            if (canonical != null && !canonical.isBlank()) {
                usable.add(locale);
            }
        }
        if (usable.isEmpty()) {
            return StepResult.degraded("canonical 无可用标题（locales=" + locales + "），保留原文");
        }

        JsonNode params = context.params();
        Double temperature = StepParams.number(params, "temperature", 0.7);
        Integer maxTokens = StepParams.integer(params, "max_tokens", 256);
        String systemPrompt = StepParams.text(params, "system_prompt", DEFAULT_SYSTEM_PROMPT);

        Map<String, String> overrides = new LinkedHashMap<>();
        try {
            for (String locale : usable) {
                String user = "语言代码：" + locale + "\n原标题：" + titles.get(locale);
                String rewritten = llm.complete(ModelRequirement.LLM,
                                new ChatRequest(null,
                                        List.of(ChatMessage.of(ChatRole.SYSTEM, systemPrompt),
                                                ChatMessage.of(ChatRole.USER, user)),
                                        temperature, maxTokens),
                                context)
                        .content();
                if (rewritten != null && !rewritten.isBlank()) {
                    overrides.put(locale, rewritten.strip());
                }
            }
        } catch (ProviderException e) {
            return StepResult.degraded("标题改写失败：" + e.getMessage() + "（回退 canonical 原文）");
        }
        if (overrides.isEmpty()) {
            return StepResult.degraded("标题改写无有效产物，回退 canonical 原文");
        }
        context.write(new FieldRef(ListingStepContext.LISTING_TITLE_OVERRIDES), overrides);
        return StepResult.ok();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        return value == null ? Map.of() : (Map<String, String>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return value == null ? List.of() : (List<String>) value;
    }
}
