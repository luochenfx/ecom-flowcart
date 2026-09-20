package io.autocommerce.worker.publish;

import io.autocommerce.core.message.Envelope;
import io.autocommerce.publish.JsonFilePublishStateStore;
import io.autocommerce.publish.PublishDecision;
import io.autocommerce.publish.PublishDisposition;
import io.autocommerce.publish.PublishService;
import io.autocommerce.publish.testsupport.FixturePublishPlatformAdapter;
import io.autocommerce.publish.testsupport.PublishFixtures;
import io.autocommerce.worker.event.EventPublisher;
import io.autocommerce.worker.event.SysWorkflowFailedEvent;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * activity 层事件纪律（AC-5 / specs/0016 §0.3）：Domain Event <b>只在事实落库之后</b>广播
 * （总线永不作 first write）。用"发事件时回读 store"的方式把顺序钉死，而非事后断言两者都存在。
 */
class PublishActivitiesImplTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse(PublishFixtures.PUBLISHED_AT), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private FixturePublishPlatformAdapter adapter;
    private JsonFilePublishStateStore store;
    private final List<String> storeAtEmit = new ArrayList<>();
    private PublishActivities activities;

    @BeforeEach
    void setUp() {
        adapter = new FixturePublishPlatformAdapter();
        store = new JsonFilePublishStateStore(tempDir.resolve("publish"));
        PublishService service = new PublishService(store, adapter, CLOCK);
        EventPublisher publisher = new EventPublisher() {
            @Override
            public void publishFailed(SysWorkflowFailedEvent event) {
                // 本 slice 不产 lifecycle 失败事件
            }

            @Override
            public void publishDomainEvent(Envelope envelope) {
                storeAtEmit.add(store.get(PublishFixtures.LISTING_ID)
                        .map(state -> "store=" + state.status())
                        .orElse("store=ABSENT"));
            }
        };
        activities = new PublishActivitiesImpl(service, publisher, "PublishWorkflow");
    }

    @Test
    void publishedEvent_emittedAfterStoreWrite() {
        PublishDecision decision = activities.publish(input());

        assertThat(decision.disposition()).isEqualTo(PublishDisposition.PUBLISHED);
        assertThat(storeAtEmit).as("发事件时 store 已有 PUBLISHED 事实（落库先行）")
                .containsExactly("store=PUBLISHED");
    }

    @Test
    void ambiguousEvent_emittedAfterStoreWrite() {
        adapter.script(FixturePublishPlatformAdapter.AddBehavior.AMBIGUOUS);

        PublishDecision decision = activities.publish(input());

        assertThat(decision.disposition()).isEqualTo(PublishDisposition.AMBIGUOUS);
        assertThat(storeAtEmit).containsExactly("store=AMBIGUOUS");
    }

    @Test
    void rejected_throwsNonRetryable_andEmitsNoDomainEvent() {
        adapter.script(FixturePublishPlatformAdapter.AddBehavior.NON_RETRYABLE);

        assertThatThrownBy(() -> activities.publish(input()))
                .isInstanceOf(ApplicationFailure.class)
                .hasMessageContaining("FX_CATEGORY_INVALID");
        assertThat(storeAtEmit).as("REJECTED 无领域事件").isEmpty();
    }

    @Test
    void retryable_throwsRetryableFailure_andEmitsNothing() {
        adapter.script(FixturePublishPlatformAdapter.AddBehavior.RETRYABLE);

        assertThatThrownBy(() -> activities.publish(input()))
                .isInstanceOf(ApplicationFailure.class);
        assertThat(storeAtEmit).isEmpty();
    }

    private static PublishWorkflowInput input() {
        return new PublishWorkflowInput(PublishFixtures.listing());
    }
}
