package io.autocommerce.core.step;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Chat token 用量（OpenAI usage 语义；落内容链执行记录供看板聚合，specs/0006 §7）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
}
