package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 供应商引用（schema: SupplierRef）。platform 恒为 "1688"（schema const）；
 * supplierId = 1688 卖家 ID（fastCreateOrder 同供应商约束依据）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SupplierRef(String platform, String supplierId, String name) {
}
