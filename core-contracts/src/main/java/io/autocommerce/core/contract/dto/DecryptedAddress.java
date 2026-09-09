package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 解密后的明文地址（AddressCapability.decryptAddress 返回）。字段为明文（非脱敏），
 * 调用方须立即 AES 加密落库（specs/0005 §8：解密触发点 = 下采购单前，1688 直发顾客需要）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DecryptedAddress(
        String receiverName,
        String receiverPhone,
        String country,
        String province,
        String city,
        String district,
        String detail,
        String postalCode) {
}
