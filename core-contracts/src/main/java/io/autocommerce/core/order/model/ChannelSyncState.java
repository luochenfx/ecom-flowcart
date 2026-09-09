package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * ChannelSyncState（渠道同步游标）—— schema: ChannelSyncState。每 channel 一行的增量拉取游标
 * （DB 位置指针，无状态机逻辑，不进 Temporal）。cursor 平台相关（modified 时间/页号等，JSONB）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChannelSyncState(String channelId, JsonNode cursor, String updatedAt) {
}
