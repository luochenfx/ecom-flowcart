package io.autocommerce.core.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 结构化错误（schema: DeadLetter.error）。class + message，非堆栈全文。
 * schema 键为 "class"（Java 保留字），经 {@code @JsonProperty} 显式映射 className → class。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeadLetterError(@JsonProperty("class") String className, String message) {
}
