package io.autocommerce.worker.publish;

import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.contract.PublishCapability;
import io.autocommerce.core.contract.dto.PlatformItemRef;
import io.autocommerce.core.message.Envelope;
import io.autocommerce.core.message.EventTypes;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractSchemas;
import io.autocommerce.publish.JsonFilePublishStateStore;
import io.autocommerce.publish.PublishService;
import io.autocommerce.publish.PublishState;
import io.autocommerce.publish.PublishStateStore;
import io.autocommerce.publish.PublishStatus;
import io.autocommerce.publish.testsupport.FailingPutPublishStateStore;
import io.autocommerce.publish.testsupport.FixturePublishPlatformAdapter;
import io.autocommerce.publish.testsupport.FixturePublishPlatformAdapter.AddBehavior;
import io.autocommerce.publish.testsupport.PublishFixtures;
import io.autocommerce.worker.event.NoopEventPublisher;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 铺货链端到端 demo（#21 AC-7 + AC-1/AC-2/AC-3/AC-4/AC-5/AC-6）：**内容就绪 Listing → 可重入检查 →
 * reconcile-first + add →（歧义挂起 + 人工 signal）→ 收敛 → 事件广播** 全链，经 fixture 假
 * {@link PublishCapability} adapter 驱动、真实域服务（{@link PublishService} / 存储）+ 真实 Temporal
 * workflow 跑（in-process test service，无需 docker / CLI）。
 *
 * <p>刻意不 mock 业务逻辑：PublishService / JsonFilePublishStateStore / PublishActivitiesImpl /
 * PublishWorkflowImpl 都是真实实现，只有外部平台是假的。三条路径（成功 / 歧义挂起 + 人工恢复 /
 * 业务拒绝收尾）全覆盖，另加：幂等复用、reconcile 回填、可重试耗尽 → FAILED、非 AdapterException → FAILED。
 *
 * <p><b>AC-6「重放安全」的覆盖口径（如实标注，避免被读成"已用 replay 测试验证"）</b>：本文件的<b>实质</b>
 * 断言覆盖「确定性」（workflowId 断言）、「幂等复用」、「AMBIGUOUS 挂起 + signal 恢复」；而
 * <b>「重放安全」是结构性保证</b>——workflow 在 {@link TestWorkflowEnvironment} 上真实执行、
 * {@code PublishWorkflowImpl} 零 I/O（由 {@code PublishRuntimeArchitectureTest} 守门），本文件
 * <b>未</b>引入显式 event-history replay 断言（全仓 {@code Replayer} 用量为 0，与 content / order /
 * purchase 三条既有 workflow E2E 的基线一致；将来补测可用一次 {@code WorkflowReplayer} 断言历史重放不分叉）。
 */
class PublishWorkflowE2ETest {

    private static final String LISTING_ID = PublishFixtures.LISTING_ID;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse(PublishFixtures.PUBLISHED_AT), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private FixturePublishPlatformAdapter adapter;
    private JsonFilePublishStateStore store;
    private NoopEventPublisher events;
    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        adapter = provider();
        store = new JsonFilePublishStateStore(tempDir.resolve("publish"));
        events = new NoopEventPublisher();
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    // ---------------- 路径 1：成功 ----------------

    @Test
    void successPath_publishesBroadcastsAndIsIdempotentOnRepeat() {
        adapter.script(AddBehavior.SUCCESS);
        startEnv();

        PublishWorkflowResult result = launcher().run(input());

        assertThat(result.platformItemId()).isEqualTo("fx-item-1");
        assertThat(result.reused()).isFalse();
        assertThat(state().status()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(state().platformItemId()).isEqualTo("fx-item-1");
        assertThat(adapter.addCalls()).hasSize(1);

        List<Envelope> published = domainEventsOf(EventTypes.LISTING_PUBLISHED);
        assertThat(published).hasSize(1);
        assertPayloadsPassSchema();

        assertThat(env.getWorkflowClient().fetchHistory(LISTING_ID).getWorkflowExecution().getWorkflowId())
                .as("确定性 workflowId = listing-{spuId}-{channelId} = listingId")
                .isEqualTo(LISTING_ID);

        // 前一次 completed → 重复指令不产生新 run、不重复 add、不重发事件（ADR-0003）
        int addsBefore = adapter.addCalls().size();
        int eventsBefore = events.publishedDomainEvents().size();
        PublishWorkflowResult repeated = launcher().run(input());
        assertThat(repeated.platformItemId()).isEqualTo(result.platformItemId());
        assertThat(adapter.addCalls()).as("completed 后重复触发不得再次 add").hasSize(addsBefore);
        assertThat(events.publishedDomainEvents()).hasSize(eventsBefore);
        assertThat(store.list()).hasSize(1);
    }

    // ---------------- 路径 2：歧义挂起 + 人工 signal 恢复 ----------------

    @Test
    void ambiguousPath_suspendsThenHumanConfirmationCompletes() {
        adapter.script(AddBehavior.AMBIGUOUS);
        startEnv();

        PublishWorkflow stub = launcher().start(input());
        awaitUntil(() -> !domainEventsOf(EventTypes.LISTING_AMBIGUOUS).isEmpty());

        // 挂起态：AMBIGUOUS 已落库 + 广播，workflow 尚未终态
        assertThat(state().status()).isEqualTo(PublishStatus.AMBIGUOUS);
        assertThat(domainEventsOf(EventTypes.LISTING_AMBIGUOUS)).hasSize(1);
        assertPayloadsPassSchema();

        // 人工确认已生效（回填平台引用）
        stub.confirmPublished("fx-item-human", "https://fixture.example.com/item/fx-item-human");
        PublishWorkflowResult result = WorkflowStub.fromTyped(stub).getResult(PublishWorkflowResult.class);

        assertThat(result.platformItemId()).isEqualTo("fx-item-human");
        assertThat(state().status()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(adapter.addCalls()).as("人工确认回填不重复 add").hasSize(1);
        assertThat(domainEventsOf(EventTypes.LISTING_PUBLISHED)).hasSize(1);
    }

    @Test
    void ambiguousPath_thenNotEffective_retriesAdd() {
        adapter.script(AddBehavior.AMBIGUOUS, AddBehavior.SUCCESS);
        startEnv();

        PublishWorkflow stub = launcher().start(input());
        awaitUntil(() -> !domainEventsOf(EventTypes.LISTING_AMBIGUOUS).isEmpty());

        stub.confirmNotEffective();
        PublishWorkflowResult result = WorkflowStub.fromTyped(stub).getResult(PublishWorkflowResult.class);

        assertThat(result.platformItemId()).isEqualTo("fx-item-1");
        assertThat(adapter.addCalls()).as("确认未生效 → 重试 add").hasSize(2);
        assertThat(state().status()).isEqualTo(PublishStatus.PUBLISHED);
    }

    @Test
    void ambiguousPath_thenReject_failsAsRejected() {
        adapter.script(AddBehavior.AMBIGUOUS);
        startEnv();

        PublishWorkflow stub = launcher().start(input());
        awaitUntil(() -> !domainEventsOf(EventTypes.LISTING_AMBIGUOUS).isEmpty());

        stub.reject("人工判定：平台侧无此商品，且拒绝重试");
        assertThatThrownBy(() -> WorkflowStub.fromTyped(stub).getResult(PublishWorkflowResult.class))
                .isInstanceOf(WorkflowException.class);

        assertThat(state().status()).isEqualTo(PublishStatus.REJECTED);
        assertThat(state().reason()).contains("人工判定");
    }

    // ---------------- 路径 3：业务拒绝收尾 ----------------

    @Test
    void rejectedPath_workflowFailsAndRerunIsAllowed() {
        adapter.script(AddBehavior.NON_RETRYABLE);
        startEnv();

        assertThatThrownBy(() -> launcher().run(input())).isInstanceOf(WorkflowException.class);
        assertThat(state().status()).isEqualTo(PublishStatus.REJECTED);
        assertThat(state().reason()).contains("FX_CATEGORY_INVALID");
        assertThat(adapter.addCalls()).hasSize(1);

        // 人工修复后重铺 = 同一 workflowId 新 run（AllowDuplicateFailedOnly 放行 failed）
        adapter.script(AddBehavior.SUCCESS);
        PublishWorkflowResult retried = launcher().run(input());
        assertThat(retried.platformItemId()).isEqualTo("fx-item-1");
        assertThat(state().status()).isEqualTo(PublishStatus.PUBLISHED);
    }

    // ---------------- 可重试：退避重试 + 耗尽 → FAILED ----------------

    @Test
    void retryableThenSuccess_retriesAndPublishes() {
        adapter.script(AddBehavior.RETRYABLE, AddBehavior.SUCCESS);
        startEnv();

        PublishWorkflowResult result = launcher().run(input());

        assertThat(result.platformItemId()).isEqualTo("fx-item-1");
        assertThat(adapter.addCalls()).as("一次 RETRYABLE 后被 RetryPolicy 重试成功").hasSize(2);
    }

    @Test
    void retryableExhausted_recordsFailedAndWorkflowFails() {
        adapter.addFallback(AddBehavior.RETRYABLE);
        startEnv();

        assertThatThrownBy(() -> launcher().run(input())).isInstanceOf(WorkflowException.class);

        assertThat(adapter.addCalls()).as("MaximumAttempts = 5").hasSize(5);
        assertThat(state().status()).isEqualTo(PublishStatus.FAILED);
    }

    // ---------------- 非 AdapterException = bug：不重试到死 ----------------

    @Test
    void nonAdapterException_isBug_recordedFailedWithoutRetry() {
        adapter.script(AddBehavior.BUG);
        startEnv();

        assertThatThrownBy(() -> launcher().run(input())).isInstanceOf(WorkflowException.class);

        assertThat(adapter.addCalls()).as("bug 按 NON_RETRYABLE 收口，不重试到死").hasSize(1);
        assertThat(state().status()).isEqualTo(PublishStatus.FAILED);
        assertThat(state().reason()).contains("IllegalStateException");
    }

    // ---------------- add 已生效、落库失败 → 挂起而非授权重铺（AC-3 / ADR-0003） ----------------

    @Test
    void addSucceedsButRecordFails_suspendsAndNeverAuthorizesRepublish() {
        adapter.script(AddBehavior.SUCCESS);
        // 注入：add 成功后的首次落库（PUBLISHED）失败；第 2 次落库（AMBIGUOUS）成功
        startEnvWithStore(new FailingPutPublishStateStore(store, 1));

        PublishWorkflow stub = launcher().start(input());
        awaitUntil(() -> !domainEventsOf(EventTypes.LISTING_AMBIGUOUS).isEmpty());

        // 原缺陷：add 已生效但落库失败被判 FAILED → workflow failed → AllowDuplicateFailedOnly 授权同 id
        // 重铺 → inspect 查不到 platform_item_id → 再次 add → 二次铺货。修复后：挂起为 AMBIGUOUS（非终态）。
        assertThat(state().status()).as("不得收口为授权重铺的 FAILED，而是挂起歧义")
                .isEqualTo(PublishStatus.AMBIGUOUS);
        assertThat(adapter.addCalls()).as("add 已生效，落库失败不得触发重发").hasSize(1);

        // 人工确认已生效（带 add 返回的平台 id）→ 落库 PUBLISHED（第 3 次落库成功）→ completed
        stub.confirmPublished("fx-item-1", "https://fixture.example.com/item/fx-item-1");
        PublishWorkflowResult result = WorkflowStub.fromTyped(stub).getResult(PublishWorkflowResult.class);

        assertThat(result.platformItemId()).isEqualTo("fx-item-1");
        assertThat(state().status()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(adapter.addCalls()).as("全程只 add 一次").hasSize(1);
    }

    @Test
    void storePersistentlyUnavailable_addOnce_suspendsNotFailed_noAmbiguousEvent() {
        adapter.script(AddBehavior.SUCCESS);
        // 注入：put 持续失败 ≥2 次（第 1 次 PUBLISHED、第 2 次 AMBIGUOUS 都写不下）→ SUSPENDED_UNRECORDED
        startEnvWithStore(new FailingPutPublishStateStore(store, 2));

        PublishWorkflow stub = launcher().start(input());
        awaitUntil(() -> adapter.addCalls().size() == 1);

        // 落库整停 → 挂起（非终态），绝不重试（旧 RETRYABLE 回退会退避重试 → 重跑 add）
        assertThat(domainEventsOf(EventTypes.LISTING_AMBIGUOUS))
                .as("事实未落库 → 绝不广播 listing.ambiguous（AC-5：总线不作 first write）").isEmpty();
        assertThat(adapter.addCalls()).as("落库失败绝不触发重发（add 恒为 1）").hasSize(1);

        // store 恢复（第 3 次 put 成功）+ 人工确认已生效 → 证明 workflow 未 failed、可恢复
        stub.confirmPublished("fx-item-1", "https://fixture.example.com/item/fx-item-1");
        PublishWorkflowResult result = WorkflowStub.fromTyped(stub).getResult(PublishWorkflowResult.class);

        assertThat(result.platformItemId()).isEqualTo("fx-item-1");
        assertThat(state().status()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(adapter.addCalls()).as("全程只 add 一次").hasSize(1);
        assertThat(domainEventsOf(EventTypes.LISTING_AMBIGUOUS)).isEmpty();
    }

    // ---------------- reconcile seam：先查后发 / 歧义核实 ----------------

    @Test
    void reconcileConfirmsExistingListing_backfillsWithoutAdd() {
        adapter.scriptReconcile(
                new PlatformItemRef("fx-item-existing", "https://fixture.example.com/item/existing"));
        startEnv();

        PublishWorkflowResult result = launcher().run(input());

        assertThat(result.platformItemId()).isEqualTo("fx-item-existing");
        assertThat(adapter.addCalls()).as("reconcile 有果 → 不重复 add").isEmpty();
        assertThat(state().status()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(domainEventsOf(EventTypes.LISTING_PUBLISHED)).hasSize(1);
    }

    @Test
    void ambiguousThenReconcileConfirms_backfillsWithoutSuspending() {
        adapter.script(AddBehavior.AMBIGUOUS)
                .scriptReconcile(null, new PlatformItemRef("fx-item-recon",
                        "https://fixture.example.com/item/recon"));
        startEnv();

        PublishWorkflowResult result = launcher().run(input());

        assertThat(result.platformItemId()).isEqualTo("fx-item-recon");
        assertThat(state().status()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(domainEventsOf(EventTypes.LISTING_AMBIGUOUS)).as("reconcile 核实到已生效 → 未挂起")
                .isEmpty();
        assertThat(adapter.addCalls()).hasSize(1);
    }

    // ---------------- 可重入：预置 PUBLISHED 事实 → 不触外部 ----------------

    @Test
    void reentrancy_prePublishedState_shortCircuitsWithoutExternalCall() {
        store.put(new PublishState(LISTING_ID, PublishStatus.PUBLISHED, "fx-item-preexisting",
                "https://fixture.example.com/item/pre", PublishFixtures.PUBLISHED_AT, null,
                PublishFixtures.PUBLISHED_AT));
        startEnv();

        PublishWorkflowResult result = launcher().run(input());

        assertThat(result.reused()).isTrue();
        assertThat(result.platformItemId()).isEqualTo("fx-item-preexisting");
        assertThat(adapter.addCalls()).isEmpty();
        assertThat(adapter.reconcileCalls()).as("可重入命中短路，不触外部调用").isEmpty();
        assertThat(events.publishedDomainEvents()).as("既有事实不重发事件").isEmpty();
    }

    // ---------------- harness ----------------

    private void startEnv() {
        startEnvWithStore(store);
    }

    private void startEnvWithStore(PublishStateStore serviceStore) {
        PublishService service =
                new PublishService(serviceStore, adapter.getCapability(PublishCapability.class), CLOCK);
        PublishActivities activities = new PublishActivitiesImpl(service, events, "PublishWorkflow");
        env = TestWorkflowEnvironment.newInstance();
        PublishWorkerFactory.register(env.newWorker(PublishRuntime.TASK_QUEUE), activities);
        env.start();
    }

    private PublishWorkflowLauncher launcher() {
        return new PublishWorkflowLauncher(env.getWorkflowClient(), PublishRuntime.TASK_QUEUE);
    }

    private static PublishWorkflowInput input() {
        return new PublishWorkflowInput(PublishFixtures.listing());
    }

    private PublishState state() {
        return store.get(LISTING_ID).orElseThrow();
    }

    private List<Envelope> domainEventsOf(String type) {
        return events.publishedDomainEvents().stream().filter(e -> type.equals(e.type())).toList();
    }

    private void assertPayloadsPassSchema() {
        for (Envelope envelope : events.publishedDomainEvents()) {
            ContractAssertions.assertValid(ContractSchemas.payloadFor(envelope.type()), envelope.payload(),
                    "事件 payload: " + envelope.type());
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断", e);
            }
        }
        throw new AssertionError("等待条件超时（10s）");
    }

    private static FixturePublishPlatformAdapter provider() {
        return ServiceLoader.load(PlatformAdapterProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> FixturePublishPlatformAdapter.PLATFORM.equals(p.platform()))
                .map(FixturePublishPlatformAdapter.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("classpath 未发现 fixture 铺货 adapter: "
                        + FixturePublishPlatformAdapter.PLATFORM + "（publish test-jar 是否在 test classpath?）"));
    }
}
