package io.autocommerce.core.step;

/**
 * LLMProvider SPI（specs/0006 §4）。core 定义 chat(request) → response 语义（对齐 OpenAI
 * chat/completions），不引 SDK；OpenAICompatProvider（配 base_url/api_key/model）为 v1 唯一
 * 内置实现，覆盖 OpenAI/本地 Ollama·vLLM/通义/DeepSeek。特殊协议平台 → 独立 provider + SPI 注册。
 */
public interface LLMProvider {

    ProviderId id();

    ChatResponse chat(ChatRequest request) throws ProviderException;
}
