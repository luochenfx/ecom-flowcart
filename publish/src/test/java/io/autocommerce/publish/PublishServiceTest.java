package io.autocommerce.publish;

import io.autocommerce.core.contract.dto.PlatformItemRef;
import io.autocommerce.publish.testsupport.FixturePublishPlatformAdapter;
import io.autocommerce.publish.testsupport.PublishFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 铺货状态机 / 幂等 / 错误归类 / reconcile seam 单测（业务模块层，AC-1/AC-2/AC-3/AC-4）。
 */
class PublishServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse(PublishFixtures.PUBLISHED_AT), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private FixturePublishPlatformAdapter adapter;
    private JsonFilePublishStateStore store;
    private PublishService service;

    @BeforeEach
    void setUp() {
        adapter = new FixturePublishPlatformAdapter();
        store = new JsonFilePublishStateStore(tempDir.resolve("publish"));
        service = new PublishService(store, adapter, CLOCK);
    }

    // ---------------- 可重入检查（§4） ----------------

    @Test
    void inspect_withoutPriorState_needsAdd() {
        assertThat(service.inspect(PublishFixtures.listing()).disposition())
                .isEqualTo(PublishDisposition.NEEDS_ADD);
    }

    @Test
    void inspect_withPublishedState_isIdempotentHit_withoutExternalCall() {
        store.put(publishedState("fx-item-pre"));

        PublishDecision decision = service.inspect(PublishFixtures.listing());

        assertThat(decision.disposition()).isEqualTo(PublishDisposition.ALREADY_PUBLISHED);
        assertThat(decision.published()).isTrue();
        assertThat(decision.reused()).isTrue();
        assertThat(decision.platformItemId()).isEqualTo("fx-item-pre");
        assertThat(adapter.addCalls()).isEmpty();
        assertThat(adapter.reconcileCalls()).isEmpty();
    }

    // ---------------- add / reconcile-first（§4 AC-1/AC-3） ----------------

    @Test
    void publish_addSucceeds_recordsPublished() {
        PublishDecision decision = service.publish(PublishFixtures.listing());

        assertThat(decision.disposition()).isEqualTo(PublishDisposition.PUBLISHED);
        assertThat(decision.platformItemId()).isEqualTo("fx-item-1");
        assertThat(decision.occurredAt()).isEqualTo(PublishFixtures.PUBLISHED_AT);
        assertThat(state().status()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(state().platformItemId()).isEqualTo("fx-item-1");
        assertThat(adapter.reconcileCalls()).as("reconcile-first：先查").hasSize(1);
        assertThat(adapter.addCalls()).hasSize(1);
    }

    @Test
    void publish_reconcileHit_backfillsWithoutAdd() {
        adapter.scriptReconcile(new PlatformItemRef("fx-item-existing", "https://fixture/item/existing"));

        PublishDecision decision = service.publish(PublishFixtures.listing());

        assertThat(decision.disposition()).isEqualTo(PublishDisposition.PUBLISHED);
        assertThat(decision.platformItemId()).isEqualTo("fx-item-existing");
        assertThat(adapter.addCalls()).as("reconcile 有果 → 不重复 add").isEmpty();
        assertThat(state().status()).isEqualTo(PublishStatus.PUBLISHED);
    }

    @Test
    void publish_alreadyPublished_doesNotAddAgain() {
        store.put(publishedState("fx-item-pre"));

        PublishDecision decision = service.publish(PublishFixtures.listing());

        assertThat(decision.disposition()).isEqualTo(PublishDisposition.ALREADY_PUBLISHED);
        assertThat(adapter.addCalls()).isEmpty();
        assertThat(adapter.reconcileCalls()).isEmpty();
    }

    @Test
    void publish_sameFactReplay_reusesPublishedAt() {
        PublishDecision first = service.publish(PublishFixtures.listing());
        PublishDecision second = service.publish(PublishFixtures.listing());

        assertThat(second.disposition()).isEqualTo(PublishDisposition.ALREADY_PUBLISHED);
        assertThat(second.occurredAt()).as("同一事实重放复用 publishedAt（保 envelope.id 稳定）")
                .isEqualTo(first.occurredAt());
        assertThat(adapter.addCalls()).hasSize(1);
    }

    // ---------------- AMBIGUOUS / reconcile（§5 AC-4） ----------------

    @Test
    void publish_addAmbiguous_noReconcile_suspendsAmbiguous() {
        adapter.script(FixturePublishPlatformAdapter.AddBehavior.AMBIGUOUS);

        PublishDecision decision = service.publish(PublishFixtures.listing());

        assertThat(decision.disposition()).isEqualTo(PublishDisposition.AMBIGUOUS);
        assertThat(decision.reason()).contains("FX_TIMEOUT");
        assertThat(decision.occurredAt()).isEqualTo(PublishFixtures.PUBLISHED_AT);
        assertThat(state().status()).isEqualTo(PublishStatus.AMBIGUOUS);
    }

    @Test
    void publish_addAmbiguous_thenReconcileConfirms_backfillsPublished() {
        adapter.script(FixturePublishPlatformAdapter.AddBehavior.AMBIGUOUS)
                .scriptReconcile(null, new PlatformItemRef("fx-item-recon", "https://fixture/item/recon"));

        PublishDecision decision = service.publish(PublishFixtures.listing());

        assertThat(decision.disposition()).isEqualTo(PublishDisposition.PUBLISHED);
        assertThat(decision.platformItemId()).isEqualTo("fx-item-recon");
        assertThat(state().status()).isEqualTo(PublishStatus.PUBLISHED);
    }

    // ---------------- 终态：REJECTED / FAILED / RETRYABLE（§3 AC-2/AC-4） ----------------

    @Test
    void publish_nonRetryable_recordsRejected() {
        adapter.script(FixturePublishPlatformAdapter.AddBehavior.NON_RETRYABLE);

        PublishDecision decision = service.publish(PublishFixtures.listing());

        assertThat(decision.disposition()).isEqualTo(PublishDisposition.REJECTED);
        assertThat(decision.reason()).contains("FX_CATEGORY_INVALID");
        assertThat(state().status()).isEqualTo(PublishStatus.REJECTED);
    }

    @Test
    void publish_retryable_doesNotRecordState() {
        adapter.script(FixturePublishPlatformAdapter.AddBehavior.RETRYABLE);

        PublishDecision decision = service.publish(PublishFixtures.listing());

        assertThat(decision.disposition()).isEqualTo(PublishDisposition.RETRYABLE);
        assertThat(store.get(PublishFixtures.LISTING_ID))
                .as("临时故障不落库（重试可能成功，不得先写终态）").isEmpty();
    }

    @Test
    void publish_nonAdapterException_recordsFailed() {
        adapter.script(FixturePublishPlatformAdapter.AddBehavior.BUG);

        PublishDecision decision = service.publish(PublishFixtures.listing());

        assertThat(decision.disposition()).isEqualTo(PublishDisposition.FAILED);
        assertThat(decision.reason()).contains("IllegalStateException");
        assertThat(state().status()).isEqualTo(PublishStatus.FAILED);
    }

    // ---------------- signal 驱动的落库（人工确认 / 拒绝） ----------------

    @Test
    void confirmPublished_recordsAndIsIdempotent() {
        PublishDecision decision = service.confirmPublished(PublishFixtures.LISTING_ID,
                "fx-item-9", "https://fixture/item/9");

        assertThat(decision.disposition()).isEqualTo(PublishDisposition.PUBLISHED);
        assertThat(decision.platformItemId()).isEqualTo("fx-item-9");
        assertThat(state().status()).isEqualTo(PublishStatus.PUBLISHED);

        PublishDecision again = service.confirmPublished(PublishFixtures.LISTING_ID,
                "fx-item-9", "https://fixture/item/9");
        assertThat(again.occurredAt()).isEqualTo(decision.occurredAt());
    }

    @Test
    void reject_andFail_recordTerminalStates() {
        assertThat(service.reject(PublishFixtures.LISTING_ID, "人工判定业务拒绝").disposition())
                .isEqualTo(PublishDisposition.REJECTED);
        assertThat(state().status()).isEqualTo(PublishStatus.REJECTED);

        assertThat(service.fail(PublishFixtures.LISTING_ID, "重试耗尽").disposition())
                .isEqualTo(PublishDisposition.FAILED);
        assertThat(state().status()).isEqualTo(PublishStatus.FAILED);
    }

    @Test
    void confirmPublished_requiresPlatformItemId() {
        assertThatThrownBy(() -> service.confirmPublished(PublishFixtures.LISTING_ID, "  ", "u"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------- helpers ----------------

    private PublishState publishedState(String platformItemId) {
        return new PublishState(PublishFixtures.LISTING_ID, PublishStatus.PUBLISHED, platformItemId,
                "https://fixture/item/pre", PublishFixtures.PUBLISHED_AT, null, PublishFixtures.PUBLISHED_AT);
    }

    private PublishState state() {
        return store.get(PublishFixtures.LISTING_ID).orElseThrow();
    }
}
