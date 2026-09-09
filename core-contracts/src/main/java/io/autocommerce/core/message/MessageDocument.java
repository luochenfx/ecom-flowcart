package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 消息文档（schema: message.schema.json 根对象，仅承载 schema_version 与可选信封样例）。
 * envelope / deadLetter 单发时直接以各自 def 校验，本容器供整文档传输形态。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MessageDocument(String schemaVersion, Envelope envelope, DeadLetterEnvelope deadLetter) {
}
