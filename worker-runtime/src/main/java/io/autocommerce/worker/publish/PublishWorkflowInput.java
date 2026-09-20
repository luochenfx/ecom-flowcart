package io.autocommerce.worker.publish;

import io.autocommerce.core.catalog.model.Listing;

import java.util.Objects;

/**
 * 铺货 workflow 输入（specs/0005 §8：Listing 是铺货 workflow 唯一输入）。
 *
 * <p>随载荷携带就绪 {@link Listing}（内容链产物，只读）；workflowId 由 {@link PublishRuntime#workflowIdFor}
 * 从 {@code spuId + channelId} 推导。边界处机械校验 {@code listingId} 与该推导一致
 * （见 {@link PublishRuntime} 的口径定稿）——不一致即早失败，不让两套口径静默分叉。
 *
 * @param listing 就绪 Listing（唯一输入）
 */
public record PublishWorkflowInput(Listing listing) {

    public PublishWorkflowInput {
        Objects.requireNonNull(listing, "listing 必填（铺货 workflow 唯一输入）");
        if (listing.listingId() == null || listing.listingId().isBlank()) {
            throw new IllegalArgumentException("listing.listingId 必填");
        }
        String expected = PublishRuntime.workflowIdFor(listing.spuId(), listing.channelId());
        if (!expected.equals(listing.listingId())) {
            throw new IllegalArgumentException("listingId 与 workflowId 口径不一致：listingId="
                    + listing.listingId() + "，期望 " + expected + "（listing-{spuId}-{channelId}）");
        }
    }

    /** 便捷取 listingId（= workflowId）。 */
    public String listingId() {
        return listing.listingId();
    }
}
