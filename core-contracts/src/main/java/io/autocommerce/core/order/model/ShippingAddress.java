package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 收货地址（schema: ShippingAddress，敏感附属字段，独立于快照的不可变承诺）。
 * 明文 AES-256-GCM 加密落库；解密/回填时机 = 下采购单前。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ShippingAddress(
        ShippingAddressState state,
        String encryptedPayload,
        ShippingAddressMask masked,
        String decryptSource) {
}
