package io.autocommerce.worker.flow;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.autocommerce.adapter.fake.FakeSalesAdapterProvider;
import io.autocommerce.adapter.fake.FakeSalesPublish;
import io.autocommerce.adapterhost.AdapterHost;
import io.autocommerce.catalog.ingest.CatalogIngestService;
import io.autocommerce.catalog.store.JsonFileCatalogStore;
import io.autocommerce.content.ContentPlan;
import io.autocommerce.content.ContentStepExecutor;
import io.autocommerce.content.spi.ContentAiStepProvider;
import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.MediaAsset;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.PublishCapability;
import io.autocommerce.publish.JsonFilePublishStateStore;
import io.autocommerce.publish.PublishService;
import io.autocommerce.publish.PublishStatus;
import io.autocommerce.worker.content.ContentChainActivities;
import io.autocommerce.worker.content.ContentChainActivitiesImpl;
import io.autocommerce.worker.content.ContentRuntime;
import io.autocommerce.worker.content.ContentWorkerFactory;
import io.autocommerce.worker.content.WorkflowCoordinates;
import io.autocommerce.worker.event.NoopEventPublisher;
import io.autocommerce.worker.publish.PublishActivities;
import io.autocommerce.worker.publish.PublishActivitiesImpl;
import io.autocommerce.worker.publish.PublishRuntime;
import io.autocommerce.worker.publish.PublishWorkflow;
import io.autocommerce.worker.publish.PublishWorkerFactory;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 编排链端到端（#72 核心 tracer bullet / specs/0007 §4–§5）：**采集产物 → 内容就绪 → 铺货收敛**全链路，
 * 全程在 in-process Temporal（{@code TestWorkflowEnvironment}）上跑，三个 task queue
 * （{@code flow-} / {@code content-} / {@code publish-}）各有真实 worker。
 *
 * <p>刻意**不 mock 业务逻辑**（对齐 {@code ContentWorkflowE2ETest} 的既有哲学）：真实 Step、真实
 * OpenAI-compatible provider、真实 {@code CatalogStore} / {@code PublishService} / {@code PublishStateStore}、
 * 真实 workflow（父链 + 两条子链）都是真实实现；只有"外面那几个 HTTP 服务"（1688 网关 / LLM / 媒体源）
 * 由 WireMock 假掉，铺货平台由 {@code adapter-fake}（走真 SPI + {@code AdapterHost} 装配）替代。
 *
 * <p>四条路径：
 * <ol>
 *   <li><b>正常收敛</b>：装配 Listing 落库 → 内容链跑完 → 铺货收敛 {@code PUBLISHED}；</li>
 *   <li><b>AMBIGUOUS 挂起 → signal 裁定 → 收敛</b>：铺货停 {@code AMBIGUOUS} ⇒ 编排链同步挂起，裁定
 *       signal 经坐 stub <b>直发铺货链</b>（不经编排链）⇒ 链路收敛；</li>
 *   <li><b>硬依赖 Step 失败 → 整链失败</b>：跨境链 {@code i18n.backfill}（critical）失败 ⇒ 内容子链
 *       failed ⇒ 编排链 failed，铺货链从未启动；</li>
 *   <li><b>内容降级 → 内容就绪断言拒绝铺货</b>（specs/0007 §5）：非硬依赖 Step 降级 ⇒ 内容子链 completed
 *       但 {@code degraded_steps} 非空 ⇒ 编排链的断言使父链 failed，铺货链从未启动。</li>
 * </ol>
 */
class ListingFlowWorkflowE2ETest {

    private static final String OFFER_ENDPOINT = "/openapi/param2/1/com.alibaba.product/alibaba.product.get";
    private static final String CHAT_ENDPOINT = "/v1/chat/completions";
    private static final String SPU_ID = "spu-1688-6688990011";
    private static final String CHANNEL_ID = "taobao-shop-a";
    private static final String LISTING_ID = "listing-" + SPU_ID + "-" + CHANNEL_ID;
    private static final String FLOW_ID = "fulfillment-" + SPU_ID + "-" + CHANNEL_ID;
    private static final List<String> LOCALES = List.of("zh-CN", "en");

    @TempDir
    Path tempDir;

    private WireMockServer server;
    private AdapterHost adapterHost;
    private JsonFileCatalogStore catalogStore;
    private JsonFilePublishStateStore publishStore;
    private FakeSalesPublish publishCapability;
    private Path mediaRoot;
    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() throws Exception {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        server.stubFor(post(urlEqualTo(OFFER_ENDPOINT))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(offerFixture())));
        server.stubFor(get(urlPathMatching("/media/.*")).atPriority(3)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "image/jpeg")
                        .withBody(new byte[] {1, 2, 3, 4})));

        catalogStore = new JsonFileCatalogStore(tempDir.resolve("catalog"));
        publishStore = new JsonFilePublishStateStore(tempDir.resolve("publish"));
        mediaRoot = tempDir.resolve("media");

        // 装配根：经真 SPI（META-INF/services + ServiceLoader）汇总 classpath 上的 Adapter
        adapterHost = AdapterHost.load();
        publishCapability = (FakeSalesPublish) adapterHost.capability(
                FakeSalesAdapterProvider.PLATFORM, PublishCapability.class);
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    // ---------------- 路径 1：正常收敛 ----------------

    @Test
    void converges_whenContentReadyAndPublishSucceeds() throws Exception {
        stubLlmSuccess();
        seedIngestedMaster();
        newEnvironment();

        ListingFlowWorkflowResult result = launcher().run(SPU_ID, CHANNEL_ID, category(), LOCALES,
                ContentChainKind.CROSS_BORDER);

        // —— 链路收敛 ——
        assertThat(result.published()).isTrue();
        assertThat(result.contentReady()).isTrue();
        assertThat(result.degradedSteps()).isEmpty();
        assertThat(result.listingId()).isEqualTo(LISTING_ID);
        assertThat(result.platformItemId()).isEqualTo("fake-item-1");

        // —— store 出现装配好的 Listing（装配是编排链第一步的职责）+ 内容链产物落库 ——
        ProductCatalog doc = catalogStore.get(SPU_ID).orElseThrow();
        Listing listing = listingOf(doc);
        assertThat(listing.listingId()).isEqualTo(LISTING_ID);
        assertThat(listing.titleOverrides()).containsKeys("zh-CN", "en");
        assertThat(listing.degradedSteps()).isNullOrEmpty();
        assertThat(doc.mediaAssets()).allMatch(m -> m.storageRef() != null && !m.storageRef().isBlank());

        // —— 铺货收敛 PUBLISHED（事实落库 + 只 add 一次） ——
        assertThat(publishStore.get(LISTING_ID).orElseThrow().status()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(publishCapability.addCalls()).hasSize(1);

        // —— 三链坐标口径一致（CONTEXT.md「链路坐标」） ——
        assertThat(historyId(FLOW_ID)).isEqualTo(FLOW_ID);
        assertThat(historyId(ContentRuntime.workflowIdFor(LISTING_ID))).isEqualTo("content-" + LISTING_ID);
        assertThat(historyId(PublishRuntime.workflowIdFor(SPU_ID, CHANNEL_ID))).isEqualTo(LISTING_ID);
    }

    // ---------------- 路径 2：AMBIGUOUS 挂起 → signal 裁定 → 收敛 ----------------

    @Test
    void suspendsOnAmbiguous_thenSignalReleasesAndConverges() throws Exception {
        stubLlmSuccess();
        seedIngestedMaster();
        publishCapability.script(FakeSalesPublish.Disposition.AMBIGUOUS);
        newEnvironment();

        ListingFlowWorkflow flow = launcher().start(SPU_ID, CHANNEL_ID, category(), LOCALES,
                ContentChainKind.DOMESTIC);

        // 铺货停在 AMBIGUOUS ⇒ 编排链同步挂起（可能持续数天；此处只验证挂起态与裁定出口）
        awaitUntil(() -> publishStore.get(LISTING_ID)
                .map(state -> state.status() == PublishStatus.AMBIGUOUS).orElse(false));
        assertThat(publishCapability.addCalls()).as("add 只发生一次（歧义不自动重发）").hasSize(1);

        // 裁定 signal 直发铺货链（specs/0007 §4.5：编排链只是跟随者，不是信号路由器）
        PublishWorkflow publish = env.getWorkflowClient().newWorkflowStub(
                PublishWorkflow.class, PublishRuntime.workflowIdFor(SPU_ID, CHANNEL_ID));
        publish.confirmPublished("fake-item-human", "https://fake-sales.example.com/item/human");

        ListingFlowWorkflowResult result = WorkflowStub.fromTyped(flow)
                .getResult(ListingFlowWorkflowResult.class);

        assertThat(result.published()).isTrue();
        assertThat(result.platformItemId()).isEqualTo("fake-item-human");
        assertThat(publishStore.get(LISTING_ID).orElseThrow().status()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(publishCapability.addCalls()).as("人工确认回填不重复 add").hasSize(1);
    }

    // ---------------- 路径 3：硬依赖 Step 失败 → 整链失败 ----------------

    @Test
    void criticalStepFailure_failsWholeChain_andNeverPublishes() throws Exception {
        stubLlmServerError(); // 跨境链 i18n.backfill（critical）失败 → 内容子链 failed
        seedIngestedMaster();
        newEnvironment();

        ListingFlowWorkflow flow = launcher().start(SPU_ID, CHANNEL_ID, category(), LOCALES,
                ContentChainKind.CROSS_BORDER);

        Throwable thrown = catchThrowable(
                () -> WorkflowStub.fromTyped(flow).getResult(ListingFlowWorkflowResult.class));

        assertThat(thrown).isInstanceOf(WorkflowException.class);
        // 装配已完成（第一步落库），但铺货链从未启动
        assertThat(catalogStore.get(SPU_ID).orElseThrow().listings()).isNotEmpty();
        assertThat(publishCapability.addCalls()).as("内容未就绪，铺货链绝不被启动").isEmpty();
        assertThat(publishStore.get(LISTING_ID)).isEmpty();
    }

    // ---------------- 路径 4：内容降级 → 内容就绪断言拒绝铺货（specs/0007 §5） ----------------

    @Test
    void degradedContent_contentReadyAssertionRefusesPublish() throws Exception {
        stubLlmServerError(); // 国内链：i18n.backfill 不在计划内 → 仅非硬依赖 Step 降级；媒体仍成功
        seedIngestedMaster();
        newEnvironment();

        ListingFlowWorkflow flow = launcher().start(SPU_ID, CHANNEL_ID, category(), LOCALES,
                ContentChainKind.DOMESTIC);

        Throwable thrown = catchThrowable(
                () -> WorkflowStub.fromTyped(flow).getResult(ListingFlowWorkflowResult.class));

        assertThat(thrown).isInstanceOf(WorkflowException.class);
        assertThat(messagesOf(thrown)).contains("内容未就绪");
        // 内容子链自身 completed（降级不阻断），但降级留痕非空 → 编排链的硬前置拒绝铺货
        Listing listing = listingOf(catalogStore.get(SPU_ID).orElseThrow());
        assertThat(listing.degradedSteps()).isNotEmpty();
        assertThat(publishCapability.addCalls()).as("降级 Listing 不许铺货，铺货链从未启动").isEmpty();
        assertThat(publishStore.get(LISTING_ID)).isEmpty();
    }

    // ---------------- harness ----------------

    /**
     * 造"采集产物"：经真 SPI（{@code AdapterHost} 装配 1688 OfferFetch）→ catalog ingest →
     * 把媒体源指向本地 fake 端点。此时文档**不含** Listing——装配 Listing 是编排链第一步的职责。
     */
    private void seedIngestedMaster() throws Exception {
        OfferFetchCapability offerFetch = adapterHost.capability("1688", OfferFetchCapability.class);
        SourceRef sourceRef = new SourceRef("1688", "6688990011",
                "http://localhost:" + server.port() + OFFER_ENDPOINT, "2026-09-10T00:00:00Z");
        new CatalogIngestService(offerFetch, catalogStore).ingest(sourceRef);

        ProductCatalog reachable = withLocalMedia(catalogStore.get(SPU_ID).orElseThrow());
        catalogStore.put(reachable);
        assertThat(catalogStore.get(SPU_ID).orElseThrow().listings())
                .as("编排链尚未启动：master 文档此刻不应含 Listing（装配是编排链的职责）")
                .isEmpty();
    }

    /** 起 in-process Temporal：三条链各有真实 worker（跨 task queue 父子链，需两个子链队列都在线）。 */
    private void newEnvironment() {
        ContentAiStepProvider provider = ContentAiStepProvider.of(
                Map.of("llm.base_url", "http://localhost:" + server.port() + "/v1",
                        "llm.model", "fake-model",
                        "llm.api_key", "test-key"),
                mediaRoot);
        ContentChainActivities contentActivities = new ContentChainActivitiesImpl(catalogStore,
                new ContentStepExecutor(provider.steps(), Clock.systemUTC()),
                new NoopEventPublisher(),
                "ContentWorkflow",
                WorkflowCoordinates.fromActivityExecutionContext());

        PublishActivities publishActivities = new PublishActivitiesImpl(
                new PublishService(publishStore, publishCapability, Clock.systemUTC()),
                new NoopEventPublisher(), "PublishWorkflow");

        ListingFlowActivities flowActivities = new ListingFlowActivitiesImpl(catalogStore, Clock.systemUTC());

        env = TestWorkflowEnvironment.newInstance();
        ContentWorkerFactory.register(env.newWorker(ContentRuntime.TASK_QUEUE), contentActivities);
        PublishWorkerFactory.register(env.newWorker(PublishRuntime.TASK_QUEUE), publishActivities);
        ListingFlowWorkerFactory.register(env.newWorker(ListingFlowRuntime.TASK_QUEUE), flowActivities);
        env.start();
    }

    private ListingFlowWorkflowLauncher launcher() {
        return new ListingFlowWorkflowLauncher(env.getWorkflowClient(), ListingFlowRuntime.TASK_QUEUE);
    }

    private String historyId(String workflowId) {
        return env.getWorkflowClient().fetchHistory(workflowId)
                .getWorkflowExecution().getWorkflowId();
    }

    private static CategoryRef category() {
        return new CategoryRef("taobao", "5001", "数码/影音");
    }

    /** 把媒体源 URL 指向本地 fake 端点（fixture 里是真 CDN 地址，测试环境不可达）。 */
    private ProductCatalog withLocalMedia(ProductCatalog doc) {
        List<MediaAsset> media = new ArrayList<>();
        for (MediaAsset asset : doc.mediaAssets()) {
            String url = "http://localhost:" + server.port() + "/media/" + asset.mediaId() + ".jpg";
            media.add(new MediaAsset(asset.mediaId(), url, asset.storageRef(), asset.role(),
                    asset.processingState(), asset.variantOf(), asset.variantPurpose(),
                    asset.variantLocale(), asset.provenance()));
        }
        return new ProductCatalog(doc.schemaVersion(), doc.spus(), doc.skus(), doc.listings(), media);
    }

    private static Listing listingOf(ProductCatalog doc) {
        return doc.listings().stream()
                .filter(l -> LISTING_ID.equals(l.listingId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("文档内无 Listing: " + LISTING_ID));
    }

    /** 汇总异常链上的全部消息（Temporal 把业务异常包进 ApplicationFailure / WorkflowFailedException）。 */
    private static String messagesOf(Throwable thrown) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            sb.append(cause.getMessage()).append(" | ");
        }
        return sb.toString();
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

    /** LLM 端点成功桩（显式 priority：{@link #stubLlmServerError()} 与它同 URL，优先级才是确定性）。 */
    private void stubLlmSuccess() {
        server.stubFor(post(urlEqualTo(CHAT_ENDPOINT)).atPriority(1).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"chatcmpl-test","object":"chat.completion","model":"fake-model",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"ANC 便携蓝牙音箱 户外防水"},
                                     "finish_reason":"stop"}],
                         "usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}
                        """)));
    }

    /** LLM 端点 500（低优先级：{@link #stubLlmSuccess()} 存在时不被命中）。 */
    private void stubLlmServerError() {
        server.stubFor(post(urlEqualTo(CHAT_ENDPOINT)).atPriority(2).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"upstream down\"}}")));
    }

    private static String offerFixture() throws Exception {
        try (InputStream in = ListingFlowWorkflowE2ETest.class
                .getResourceAsStream("/fixtures/1688-offer-demo.json")) {
            if (in == null) {
                throw new AssertionError("demo fixture 缺失: /fixtures/1688-offer-demo.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
