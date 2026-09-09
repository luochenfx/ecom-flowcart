package io.autocommerce.core.step;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Chat 响应（specs/0006 §4 ChatResponse）。content 为生成文本；model/usage 可空。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatResponse(String content, String model, ChatUsage usage) {
}
