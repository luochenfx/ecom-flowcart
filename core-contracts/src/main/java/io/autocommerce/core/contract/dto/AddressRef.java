package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 地址解密请求引用（AddressCapability.decryptAddress 入参）。
 * platformOrderNo 定位平台侧订单；platformRaw 携带解密所需原文（OAID/加密字段等，可空）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AddressRef(String platform, String platformOrderNo, String maskedPayload) {
}
