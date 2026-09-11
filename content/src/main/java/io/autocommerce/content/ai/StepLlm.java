package io.autocommerce.content.ai;

import io.autocommerce.core.step.ChatMessage;
import io.autocommerce.core.step.ChatRequest;
import io.autocommerce.core.step.ChatRole;
import io.autocommerce.core.step.ModelRequirement;
import io.autocommerce.core.step.StepContext;

import java.util.List;

/**
 * Step 侧 LLM 调用样板（#41 review 抽口）：system + user 两条消息、按 Step 参数档位出请求、
 * 返回**原始 content**（可能 null / 空白）。
 *
 * <p>为什么止步于"返回原始 content"而不在这里判空：空返回的含义**随 Step 而变**——硬依赖
 * （{@code i18n.backfill}）空 = 没有内容不可铺 → 抛 {@code ProviderException}；可降级
 * （{@code title.rewrite} / {@code desc.generate}）空 = 该 locale 跳过、铺货回退 canonical → 降级。
 * 调用样板可以共用，**语义不能共用**，所以助手只负责前者。
 *
 * <p>三个 Step 原本各自内联这段 {@code ChatRequest} 构造（#41 review 的标准 Duplicated Code）。
 */
final class StepLlm {

    private StepLlm() {
    }

    /**
     * 单次 chat/completions。
     *
     * @param llm           Step 侧调用入口（provider/model 由 {@code ModelResolver} 解析，此处不感知）
     * @param context       当前 Step 上下文（解析点读 params 做档位覆盖；用量记入其 UsageRecorder）
     * @param temperature   采样温度（null = 用后端默认）
     * @param maxTokens     输出上限（null = 用后端默认）
     * @throws io.autocommerce.core.step.ProviderException 端点失败
     */
    static String complete(LlmGateway llm, StepContext context, String systemPrompt, String user,
                           Double temperature, Integer maxTokens) {
        return llm.complete(ModelRequirement.LLM,
                        new ChatRequest(null,
                                List.of(ChatMessage.of(ChatRole.SYSTEM, systemPrompt),
                                        ChatMessage.of(ChatRole.USER, user)),
                                temperature, maxTokens),
                        context)
                .content();
    }
}
