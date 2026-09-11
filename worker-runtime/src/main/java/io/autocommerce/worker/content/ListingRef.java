package io.autocommerce.worker.content;

/**
 * 文档坐标（activity 入参的最小单元）：CatalogStore 以 spuId 为键，Listing 以 listingId 定位。
 *
 * <p>独立成 record 而非散落两个 String 参数：Temporal 跨 activity 边界传的是自描述 payload，
 * 坐标同进同出，避免"只传了 listingId 却不知道去哪取文档"的调用面。
 */
public record ListingRef(String spuId, String listingId) {

    public ListingRef {
        if (spuId == null || spuId.isBlank()) {
            throw new IllegalArgumentException("spuId 必填（CatalogStore 文档键）");
        }
        if (listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("listingId 必填（内容链目标 Listing）");
        }
    }
}
