package io.autocommerce.worker.publish;

import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.publish.testsupport.PublishFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * workflowId 口径（AC-1 / 开放点 #1）：确定性构造 + 与 {@code Listing.listingId} 重合的不变量。
 */
class PublishRuntimeTest {

    @Test
    void workflowId_isDeterministicListingForm() {
        assertThat(PublishRuntime.workflowIdFor("spu-1", "shop-a")).isEqualTo("listing-spu-1-shop-a");
        assertThat(PublishRuntime.workflowIdFor(PublishFixtures.SPU_ID, PublishFixtures.CHANNEL_ID))
                .as("workflowId 与 Listing.listingId 重合（productId = Listing.spuId）")
                .isEqualTo(PublishFixtures.LISTING_ID);
    }

    @Test
    void blankArgumentsRejected() {
        assertThatThrownBy(() -> PublishRuntime.workflowIdFor(null, "c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PublishRuntime.workflowIdFor("  ", "c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PublishRuntime.workflowIdFor("p", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inputAcceptsCanonicalListing() {
        assertThat(new PublishWorkflowInput(PublishFixtures.listing()).listingId())
                .isEqualTo(PublishFixtures.LISTING_ID);
    }

    @Test
    void inputRejectsListingIdWorkflowIdMismatch() {
        Listing base = PublishFixtures.listing();
        Listing mismatched = new Listing("listing-wrong", base.spuId(), base.channelId(),
                base.titleOverrides(), base.descriptionOverrides(), base.locales(), base.platformCategory(),
                base.platformAttributes(), base.specMappings(), base.skuSet(), base.images(),
                base.degradedSteps(), base.provenance());

        assertThatThrownBy(() -> new PublishWorkflowInput(mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("listingId 与 workflowId 口径不一致");
    }
}
