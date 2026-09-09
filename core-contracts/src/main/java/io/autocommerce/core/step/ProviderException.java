package io.autocommerce.core.step;

/**
 * LLMProvider 异常（Provider 侧失败）。OpenAI-compatible 语义、HTTP 直连不引 SDK；
 * 重试/超时语义由 content workflow 的 Activity RetryPolicy 消费。
 */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
