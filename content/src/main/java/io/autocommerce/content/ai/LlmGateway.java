package io.autocommerce.content.ai;

import io.autocommerce.core.step.ChatRequest;
import io.autocommerce.core.step.ChatResponse;
import io.autocommerce.core.step.ModelRequirement;
import io.autocommerce.core.step.ProviderException;
import io.autocommerce.core.step.StepContext;

/**
 * Step 侧 LLM 调用入口（content 内部端口）：Step 只表达"要什么样的模型"（{@link ModelRequirement}）
 * 与"发什么消息"，"用哪个 provider / 哪个 model"由 {@code ModelResolver} 解析点决定
 * （specs/0006 §4：v1 静态映射，未来编排器同接口路由）。
 *
 * <p>这样 Step 声明里不含任何 provider/model 硬编码——换后端、切档位、未来上编排器都不动 Step。
 */
public interface LlmGateway {

    /**
     * 按 model_requirement 解析目标模型并调用。
     *
     * @param requirement Step 的模型需求（v1 仅 {@link ModelRequirement#LLM} 有模型语义）
     * @param request     消息与采样参数（model 字段可空——由解析点填充）
     * @param context     当前 Step 上下文（解析点可读 params 做档位覆盖；用量记入其 UsageRecorder）
     * @throws ProviderException 端点失败（重试/超时由编排层 RetryPolicy 兜）
     */
    ChatResponse complete(ModelRequirement requirement, ChatRequest request, StepContext context)
            throws ProviderException;
}
