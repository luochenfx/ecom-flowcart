package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 铺货结果（PublishCapability.add 返回）。platformItemId 回填 Listing images 的
 * platform_media_id 语义与 execution_projection（specs/0001）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PublishResult(String listingId, String platformItemId, String url) {
}
