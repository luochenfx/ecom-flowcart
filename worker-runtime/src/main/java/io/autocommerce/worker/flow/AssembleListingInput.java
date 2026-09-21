package io.autocommerce.worker.flow;

import io.autocommerce.core.catalog.model.CategoryRef;

import java.util.List;
import java.util.Objects;

/**
 * 装配 Listing 的 activity 入参（specs/0007 §4.4 步 1）：从 master 文档派生待就绪 Listing 所需的参数。
 *
 * <p>独立成 record（与 {@code ContentStepInput} / {@code ListingRef} 同例）：坐标与装配参数同进同出，
 * 避免"只传了 half 参数却不知道该装配到哪个渠道"的调用面。
 */
public record AssembleListingInput(String spuId, String channelId, CategoryRef targetCategory,
                                   List<String> locales) {

    public AssembleListingInput {
        if (spuId == null || spuId.isBlank()) {
            throw new IllegalArgumentException("spuId 必填（CatalogStore 文档键）");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId 必填（目标渠道账号）");
        }
        Objects.requireNonNull(targetCategory, "目标平台叶子类目（targetCategory）必填");
        if (locales == null || locales.isEmpty()) {
            throw new IllegalArgumentException("locales 必填（提交用语言集）");
        }
        locales = List.copyOf(locales);
    }
}
