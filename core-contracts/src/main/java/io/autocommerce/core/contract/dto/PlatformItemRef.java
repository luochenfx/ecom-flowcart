package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 平台商品级引用（PublishResult 产物）。url 可空（平台不返回链接时）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PlatformItemRef(String platformItemId, String url) {
}
