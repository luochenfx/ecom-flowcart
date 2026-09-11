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
