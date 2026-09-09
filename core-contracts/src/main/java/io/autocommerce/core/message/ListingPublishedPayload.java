package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * listing.published 事件负载（schema: ListingPublishedPayload）。铺货成功（PUBLISHED 终态）广播，
 * 回填 platform_item_id。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ListingPublishedPayload(String listingId, String platformItemId, String publishedAt) {
}
