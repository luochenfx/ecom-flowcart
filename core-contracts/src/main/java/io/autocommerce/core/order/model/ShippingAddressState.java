package io.autocommerce.core.order.model;

/**
 * 收货地址状态（schema: ShippingAddress.state）。
 * MASKED = 平台给的是脱敏值；DECRYPTED = 已通过平台解密 API 取明文并加密落库；EMPTY = 平台未给。
 */
public enum ShippingAddressState {
    MASKED,
    DECRYPTED,
    EMPTY
}
