package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * listing.ambiguous 事件负载（schema: ListingAmbiguousPayload）。铺货超时歧义挂起
 * （等 reconcile/人工，供看板 HITL）。reason = Ambiguous 结构化原因（platform_code + 描述）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ListingAmbiguousPayload(String listingId, String reason) {
}
