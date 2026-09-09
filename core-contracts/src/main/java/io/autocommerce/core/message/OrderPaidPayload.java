package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.math.BigDecimal;

/**
 * order.paid 事件负载（schema: OrderPaidPayload）。业务事实已落库后广播；消费端回读 Order 与快照。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderPaidPayload(String orderId, BigDecimal paymentAmount, String currency, String paidAt) {
}
