package io.autocommerce.core.step;

/**
 * LLM Provider 标识（specs/0006 §4 ProviderId）。"openai" / "local-ollama" / "dashscope" …
 */
public record ProviderId(String value) {
}
