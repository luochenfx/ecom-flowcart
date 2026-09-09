package io.autocommerce.core.step;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Chat 消息（OpenAI chat/completions 语义对齐）。content 为字符串或结构化内容（JsonNode 直通）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatMessage(ChatRole role, Object content) {

    public static ChatMessage of(ChatRole role, String content) {
        return new ChatMessage(role, content);
    }
}
