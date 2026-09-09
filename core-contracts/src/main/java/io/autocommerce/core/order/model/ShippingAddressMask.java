package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 收货地址掩码（schema: ShippingAddressMask）。下单时平台给的地址原样（可能脱敏）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ShippingAddressMask(
        String country,
        String province,
        String city,
        String district,
        String detail,
        String postalCode,
        String receiverName,
        String receiverPhone) {
}
