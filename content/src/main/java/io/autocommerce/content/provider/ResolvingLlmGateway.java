package io.autocommerce.content.provider;

import io.autocommerce.core.step.ChatRequest;
import io.autocommerce.core.step.ChatResponse;
import io.autocommerce.core.step.LLMProvider;
import io.autocommerce.core.step.ModelRequirement;
import io.autocommerce.core.step.ModelResolver;
import io.autocommerce.core.step.ProviderException;
import io.autocommerce.core.step.ResolvedModel;
import io.autocommerce.core.step.StepContext;
import io.autocommerce.content.ai.LlmGateway;
import io.autocommerce.content.model.UsageRecorder;

import java.util.Objects;

/**
 * {@link LlmGateway} 的实现：把"解析点 + provider 表"组合起来（specs/0006 §4）。
 *
 * <pre>
 * Step → LlmGateway.complete(requirement, request, ctx)
 *          → ModelResolver.resolve(requirement, ctx)   // v1 静态映射；未来编排器同接口路由
 *          → ProviderRegistry.get(providerId).chat(...) // OpenAI-compatible HTTP 直连
 *          → ctx 记录 usage（token 用量 → 内容链执行记录，specs/0006 §7）
 * </pre>
 *
 * <p><b>重审触发条件（#43 标注，本 ticket 不动代码）</b>：单 provider 下本类几乎纯委托——
 * {@code ModelResolver} 只做静态映射，解析这一步还没长出真实逻辑（usage 记录是它唯一的自有职责）。
 * 但它是 specs/0006 §4 显式预留的 seam（未来编排器按同一接口路由），**不是多余的中间层**：
 * 等出现第 2 个 provider 或第 2 条 model_requirement 路由规则时回来重审——若那时解析仍恒等于
 * "永远返回同一个 providerId + 同一个 model"，才该考虑把本层并进调用方。
 */
public final class ResolvingLlmGateway implements LlmGateway {

    private final ModelResolver resolver;
    private final ProviderRegistry registry;

    public ResolvingLlmGateway(ModelResolver resolver, ProviderRegistry registry) {
        this.resolver = Objects.requireNonNull(resolver, "ModelResolver 必填");
        this.registry = Objects.requireNonNull(registry, "ProviderRegistry 必填");
    }

    @Override
    public ChatResponse complete(ModelRequirement requirement, ChatRequest request, StepContext context)
            throws ProviderException {
        ResolvedModel resolved = resolver.resolve(requirement, context);
        LLMProvider provider = registry.get(resolved.providerId());
        ChatResponse response = provider.chat(new ChatRequest(resolved.model(), request.messages(),
                request.temperature(), request.maxTokens()));
        if (context instanceof UsageRecorder recorder) {
            String model = response.model() == null || response.model().isBlank()
                    ? resolved.model()
                    : response.model();
            recorder.recordUsage(model, response.usage());
        }
        return response;
    }
}
