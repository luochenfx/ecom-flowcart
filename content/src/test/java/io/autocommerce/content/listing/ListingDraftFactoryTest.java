package io.autocommerce.content.listing;

import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.content.testsupport.ContentDocs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Listing 装配（内容链输入的建单口）：确定性 id、成本价占位、图/语言/locale 就位。
 */
class ListingDraftFactoryTest {

    private final ListingDraftFactory factory = new ListingDraftFactory(ContentDocs.FIXED_CLOCK);

    @Test
    void derivesDeterministicListing_fromMaster() {
        var master = ContentDocs.master();

        var document = factory.draft(master, ContentDocs.CHANNEL_ID,
                new CategoryRef("taobao", "5001", "数码/影音"), List.of("zh-CN", "en"));

        assertThat(document.listings()).hasSize(1);
        Listing listing = document.listings().get(0);
        assertThat(listing.listingId()).isEqualTo("listing-spu-1688-6688990011-taobao-shop-a");
        assertThat(listing.spuId()).isEqualTo(ContentDocs.SPU_ID);
        assertThat(listing.channelId()).isEqualTo(ContentDocs.CHANNEL_ID);
        assertThat(listing.locales()).containsExactly("zh-CN", "en");
        assertThat(listing.platformCategory().taxonomy()).isEqualTo("taobao");
        // 初始价 = 成本价（占位，待 price.strategy 覆盖）
        assertThat(listing.skuSet()).extracting(s -> s.price().amount()).containsExactly("45.90", "52.00");
        // 媒体引用 SPU 资产，platform_media_id 待铺货回填
        assertThat(listing.images()).extracting("mediaId")
                .containsExactly("media-1688-6688990011-0", "media-1688-6688990011-1");
        assertThat(listing.images()).allMatch(i -> i.platformMediaId() == null);
        assertThat(listing.provenance().createdByStep()).isEqualTo(ProvenanceStep.LISTING);
        assertThat(listing.provenance().parentRef()).isEqualTo(ContentDocs.SPU_ID);
        assertThat(listing.degradedSteps()).isNull();
    }

    @Test
    void rejectsMissingInputs() {
        var master = ContentDocs.master();
        CategoryRef category = new CategoryRef("taobao", "5001", null);

        assertThatThrownBy(() -> factory.draft(master, " ", category, List.of("zh-CN")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("channelId");
        assertThatThrownBy(() -> factory.draft(master, "ch", null, List.of("zh-CN")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("叶子类目");
        assertThatThrownBy(() -> factory.draft(master, "ch", category, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("locales");
    }
}
