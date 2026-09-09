package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 订单增量拉取游标（specs/0005 §8）。cursor 平台相关（modified 时间/页号等，JSONB），
 * 与 order ChannelSyncState.cursor 同源；由 Adapter 解释并推进。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SyncCursor(String channelId, JsonNode cursor) {
}
