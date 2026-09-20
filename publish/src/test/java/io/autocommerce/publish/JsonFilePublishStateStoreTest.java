package io.autocommerce.publish;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFilePublishStateStoreTest {

    @TempDir
    Path tempDir;

    private JsonFilePublishStateStore store() {
        return new JsonFilePublishStateStore(tempDir.resolve("publish"));
    }

    private static PublishState state(String listingId, PublishStatus status, String platformItemId) {
        return new PublishState(listingId, status, platformItemId, "https://fixture/item/1",
                "2026-09-10T08:00:00Z", null, "2026-09-10T08:00:00Z");
    }

    @Test
    void putThenGet_roundTrips() {
        store().put(state("listing-a", PublishStatus.PUBLISHED, "fx-1"));

        PublishState loaded = store().get("listing-a").orElseThrow();
        assertThat(loaded.status()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(loaded.platformItemId()).isEqualTo("fx-1");
    }

    @Test
    void putSameListingId_overwrites() {
        store().put(state("listing-a", PublishStatus.AMBIGUOUS, null));
        store().put(state("listing-a", PublishStatus.PUBLISHED, "fx-1"));

        assertThat(store().list()).hasSize(1);
        assertThat(store().get("listing-a").orElseThrow().status()).isEqualTo(PublishStatus.PUBLISHED);
    }

    @Test
    void list_returnsAllRows() {
        store().put(state("listing-a", PublishStatus.PUBLISHED, "fx-1"));
        store().put(state("listing-b", PublishStatus.FAILED, null));

        assertThat(store().list()).extracting(PublishState::listingId)
                .containsExactlyInAnyOrder("listing-a", "listing-b");
    }

    @Test
    void get_missingOrBlank_returnsEmpty() {
        assertThat(store().get("nope")).isEmpty();
        assertThat(store().get("  ")).isEmpty();
    }
}
