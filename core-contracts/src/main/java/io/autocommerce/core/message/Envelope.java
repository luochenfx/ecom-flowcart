package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * envelope（transport-agnostic 统一信封，schema: Envelope）。字段与 CloudEvents 对齐但自研轻量、
 * 不引 SDK；总线可换、契约不破（ADR-0001/0006）。
 * payload 为轻量 JsonNode 直通（实体引用 + 变化摘要），数据真相在业务库、消费端回读；
 * 负载不作 first write。id 幂等锚（uuid）；type 见 {@link EventTypes}；version 语义化。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Envelope(
        String id,
        String type,
        String version,
        String occurredAt,
        String producer,
        EntityRef entityRef,
        String correlationId,
        String traceId,
        JsonNode payload) {
}
