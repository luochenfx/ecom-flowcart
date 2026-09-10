package io.autocommerce.content.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import io.autocommerce.core.step.ModelRequirement;
import io.autocommerce.core.step.ModelResolver;
import io.autocommerce.core.step.ResolvedModel;
import io.autocommerce.core.step.StepContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * v1 模型解析点实现（specs/0006 §4）：{@code model_requirement → (provider, model)} 静态映射。
 *
 * <p>这是"编排插槽预留、v1 不做编排"的落点：v1 = 装配点写死映射（换 base_url/model 即全局换后端）；
 * 未来编排器**在同一个接口**上实现按 Step/成本/质量路由——core 契约与 Step 声明都不改。
 *
 * <p>档位切换（#20 AC-8"provider 档位切换"）：Step 可在自己的 params 里写 {@code "model": "..."}
 * 覆盖映射表里的模型名（provider 不变）——单 Step 换档零配置代码。
 *
 * <p>只有 LLM 需求有模型语义：{@code RULE}（零模型）与 {@code MEDIA_PROCESSOR}（走 MediaProcessor
 * 分型，specs/0006 §1）不经本解析点——误配即抛，不静默兜底。
 */
public final class StaticModelResolver implements ModelResolver {

    /** Step params 中的模型档位覆盖键。 */
    public static final String PARAM_MODEL = "model";

    private final Map<ModelRequirement, ResolvedModel> mapping;

    public StaticModelResolver(Map<ModelRequirement, ResolvedModel> mapping) {
        Objects.requireNonNull(mapping, "mapping 必填");
        this.mapping = Map.copyOf(new LinkedHashMap<>(mapping));
    }

    /** v1 常见形态：单模型跑全部 LLM Step。 */
    public static StaticModelResolver singleLlm(ResolvedModel llm) {
        return new StaticModelResolver(Map.of(ModelRequirement.LLM, llm));
    }

    @Override
    public ResolvedModel resolve(ModelRequirement requirement, StepContext context) {
        if (requirement == null) {
            throw new IllegalArgumentException("model_requirement 必填（Step 声明缺失）");
        }
        ResolvedModel resolved = mapping.get(requirement);
        if (resolved == null) {
            throw new IllegalStateException("ModelResolver 未配置该 model_requirement: " + requirement
                    + "（v1 静态映射由装配点写入；RULE / MEDIA_PROCESSOR 无模型语义，不应经解析点）");
        }
        String override = modelOverride(context);
        return override == null ? resolved : new ResolvedModel(resolved.providerId(), override);
    }

    private static String modelOverride(StepContext context) {
        if (context == null) {
            return null;
        }
        JsonNode params = context.params();
        if (params == null) {
            return null;
        }
        JsonNode model = params.get(PARAM_MODEL);
        if (model == null || !model.isTextual() || model.asText().isBlank()) {
            return null;
        }
        return model.asText();
    }
}
