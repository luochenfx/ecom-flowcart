package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * sys.message.dead_lettered 事件负载（schema: SysMessageDeadLetteredPayload，Lifecycle 事件）。
 * DLQ 落库时的广播信号（告警/看板）。重放素材在 DLQ 队列原消息，本事件只做信号。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SysMessageDeadLetteredPayload(
        String queue,
        String originalMessageId,
        String reason,
        String deadAt) {
}
