package io.autocommerce.core.step;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;

/**
 * Chat 请求（specs/0006 §4 ChatRequest，OpenAI chat/completions 语义对齐；HTTP 直连不引 SDK）。
 * model 可空（由 ModelResolver/Provider 默认决定）；temperature/maxTokens 可空（用 Provider 默认）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        Integer maxTokens) {
}
