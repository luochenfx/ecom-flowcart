package io.autocommerce.worker.content;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.autocommerce.catalog.ingest.CatalogIngestService;
import io.autocommerce.catalog.store.JsonFileCatalogStore;
import io.autocommerce.content.ContentPlan;
import io.autocommerce.content.ContentStepExecutor;
import io.autocommerce.content.ContentStepRun;
import io.autocommerce.content.listing.ListingDraftFactory;
import io.autocommerce.content.spi.ContentAiStepProvider;
import io.autocommerce.core.catalog.model.CategoryRef;
import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.MediaAsset;
import io.autocommerce.core.catalog.model.ProcessingState;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractObjectMapper;
import io.autocommerce.core.testutil.ContractSchemas;
import io.autocommerce.core.step.StepOutcome;
import io.temporal.client.WorkflowException;
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
import java.util.ServiceLoader;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 内容链端到端 Demo（#20 AC-1 / AC-9）：**采集 → 建单 → 内容链 → 内容就绪**全链路，全程无需真实模型。
 *
 * <p>与 #19 的 demo 串联关系：本测试复用 #19 的采集入口（SPI 装配 1688 OfferFetch → catalog ingest →
 * JsonFileCatalogStore），在其产物上装配待就绪 Listing，再交给 Temporal workflow 跑内容链。四条外部
 * 依赖全部由 WireMock 提供：
 * <ul>
 *   <li>1688 网关（offer fixture；与 catalog demo fixture 同源镜像）；</li>
 *   <li>OpenAI-compatible LLM 端点（成功 / 500 两态）；</li>
 *   <li>媒体源（图片字节），供 media.process 归档下载。</li>
 * </ul>
 *
 * <p>刻意**不 mock 业务逻辑**：Step、OpenAI-compatible provider、CatalogStore、Temporal workflow 都是
 * 真实实现，只有"外面那几个 HTTP 服务"是假的——这正是 AC-9 要的"fake LLM 端点即可"。
 */
class ContentWorkflowE2ETest {

    private static final String OFFER_ENDPOINT = "/openapi/param2/1/com.alibaba.product/alibaba.product.get";
    private static final String CHAT_ENDPOINT = "/v1/chat/completions";
    private static final String SPU_ID = "spu-1688-6688990011";
    private static final String CHANNEL_ID = "taobao-shop-a";
    private static final String LISTING_ID = "listing-" + SPU_ID + "-" + CHANNEL_ID;

    @TempDir
    Path tempDir;

    private WireMockServer server;
    private JsonFileCatalogStore store;
    private Path mediaRoot;

    @BeforeEach
    void startGateways() throws Exception {
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
        store = new JsonFileCatalogStore(tempDir.resolve("catalog"));
        mediaRoot = tempDir.resolve("media");
    }

    @AfterEach
    void stopGateways() {
        if (server != null) {
            server.stop();
        }
    }

    // ---------------- 场景 1：全链路收敛为内容就绪 ----------------

    @Test
    void convergesToContentReady_forIngestedListing() throws Exception {
        stubLlmSuccess();
        seedMasterReadyListing();

        TestWorkflowEnvironment env = newEnvironment();
        try {
            ContentWorkflowResult result = launcher(env).run(
                    new ContentWorkflowInput(SPU_ID, LISTING_ID, ContentPlan.standard()));

            // —— 链路收敛 ——
            assertThat(result.contentReady()).isTrue();
            assertThat(result.runs()).extracting(ContentStepRun::stepId).containsExactly(
                    ContentPlan.I18N_BACKFILL, ContentPlan.TITLE_REWRITE, ContentPlan.DESC_GENERATE,
                    ContentPlan.PRICE_STRATEGY, ContentPlan.MEDIA_PROCESS);
            assertThat(result.runs()).allMatch(r -> r.outcome() == StepOutcome.OK);
            assertThat(result.degradedSteps()).isEmpty();

            // —— 执行记录带模型用量（看板聚合口径，非计费系统） ——
            ContentStepRun rewrite = step(result, ContentPlan.TITLE_REWRITE);
            assertThat(rewrite.model()).isEqualTo("fake-model");
            // 标题改写按 locale 各调一次（zh-CN + en），每次 fake 端回报 total_tokens=20 → 累计 40
            assertThat(rewrite.totalTokens()).isEqualTo(40);
            ContentStepRun price = step(result, ContentPlan.PRICE_STRATEGY);
            assertThat(price.model()).isNull();
            assertThat(price.totalTokens()).isNull();

            // —— 产物落库（单稿制 provenance + 各字段） ——
            ProductCatalog doc = store.get(SPU_ID).orElseThrow();
            Listing listing = listing(doc);
            assertThat(listing.provenance().updatedByStep()).isEqualTo(ProvenanceStep.AI);
            assertThat(listing.provenance().createdByStep()).isEqualTo(ProvenanceStep.LISTING);
            assertThat(listing.titleOverrides()).containsKeys("zh-CN", "en");
            assertThat(listing.descriptionOverrides()).containsKeys("zh-CN", "en");
            // 译文回填 master canonical（一处翻译多处复用）
            assertThat(doc.spus().get(0).titles()).containsKeys("zh-CN", "en");
            // 价格策略：成本价 × 1.80（45.90 → 82.62）
            assertThat(listing.skuSet()).extracting(s -> s.price().amount()).containsExactly("82.62", "82.62");
            // 媒体归档：RAW → DOWNLOADED 且 storage_ref 就位
            assertThat(doc.mediaAssets()).allMatch(m -> m.processingState() == ProcessingState.DOWNLOADED);
            assertThat(doc.mediaAssets()).allMatch(m -> m.storageRef() != null && !m.storageRef().isBlank());

            // —— 契约门：内容就绪文档仍过 product-catalog schema ——
            ContractAssertions.assertValid(ContractSchemas.productCatalog(),
                    ContractObjectMapper.create().valueToTree(doc), "内容就绪 Listing 文档");

            // —— 确定性 workflowId 确实生效（ADR-0003 幂等口径） ——
            assertThat(env.getWorkflowClient().fetchHistory(ContentRuntime.workflowIdFor(LISTING_ID))
                    .getWorkflowExecution().getWorkflowId()).isEqualTo("content-" + LISTING_ID);

            // —— 前一次 completed → 重复启动不产生新 run、不重复烧钱（ADR-0003："重复指令命中
            //    AlreadyStarted 即视为重复提交并返回既有 execution"） ——
            int chatCallsBefore = chatCalls();
            ContentWorkflowResult repeated = launcher(env).run(
                    new ContentWorkflowInput(SPU_ID, LISTING_ID, ContentPlan.standard()));
            assertThat(chatCalls()).as("completed 后重复启动不得再次调用模型").isEqualTo(chatCallsBefore);
            assertThat(repeated.runs()).extracting(ContentStepRun::stepId).containsExactly(
                    ContentPlan.I18N_BACKFILL, ContentPlan.TITLE_REWRITE, ContentPlan.DESC_GENERATE,
                    ContentPlan.PRICE_STRATEGY, ContentPlan.MEDIA_PROCESS);
        } finally {
            env.close();
        }
    }

    // ---------------- 场景 2：非硬依赖降级不阻断链路 ----------------

    @Test
    void optionalStepFailure_degradesButStillConverges() throws Exception {
        stubLlmServerError();
        seedMasterReadyListing();

        TestWorkflowEnvironment env = newEnvironment();
        try {
            ContentWorkflowResult result = launcher(env).run(new ContentWorkflowInput(SPU_ID, LISTING_ID,
                    ContentPlan.standard().without(ContentPlan.I18N_BACKFILL)));

            // 链路仍收敛；失败的两步留痕（看板 HITL 复核）
            assertThat(result.contentReady()).isTrue();
            assertThat(result.degradedStepIds()).containsExactly(
                    ContentPlan.TITLE_REWRITE, ContentPlan.DESC_GENERATE);
            assertThat(result.runs()).filteredOn(r -> r.outcome() == StepOutcome.DEGRADED)
                    .extracting(ContentStepRun::stepId)
                    .containsExactly(ContentPlan.TITLE_REWRITE, ContentPlan.DESC_GENERATE);

            ProductCatalog doc = store.get(SPU_ID).orElseThrow();
            Listing listing = listing(doc);
            // 降级产物 = 不写 overrides（铺货回退 canonical）
            assertThat(listing.titleOverrides()).isNull();
            assertThat(listing.descriptionOverrides()).isNull();
            assertThat(listing.degradedSteps()).hasSize(2);
            // 其余 Step 产物照落：价格与媒体不受影响
            assertThat(listing.skuSet()).extracting(s -> s.price().amount()).containsExactly("82.62", "82.62");
            assertThat(doc.mediaAssets()).allMatch(m -> m.processingState() == ProcessingState.DOWNLOADED);
        } finally {
            env.close();
        }
    }

    // ---------------- 场景 3：硬依赖失败 → 失败后重跑，清理陈旧降级留痕 ----------------

    /**
     * 幂等口径 = {@code AllowDuplicateFailedOnly}（specs/0006 §9 / ADR-0003）：前一次 **failed** 放行
     * 新 run，前一次 **completed** 拒绝新 run。
     *
     * <p>本用例走的正是"允许重跑"的那条路径，并钉住一个容易漏的后果：硬依赖在链路尾部失败时，
     * 前面已经落库的降级留痕会留在文档里；重跑时若这些 Step 已经成功，留痕必须被清掉——
     * 否则看板对着一份内容已补齐的 Listing 一直亮 HITL。
     */
    @Test
    void rerunAfterHardFailure_clearsStaleDegradedSteps() throws Exception {
        stubLlmServerError();    // 标题 / 描述 → 非硬依赖降级（留痕落库）
        stubMediaServerError();  // 媒体 → 硬依赖失败（workflow failed，由此获得重跑许可）
        seedMasterReadyListing();
        ContentPlan plan = ContentPlan.standard().without(ContentPlan.I18N_BACKFILL);

        TestWorkflowEnvironment env = newEnvironment();
        try {
            ContentWorkflowLauncher launcher = launcher(env);

            // 第一轮：降级 + 硬依赖失败
            Throwable thrown = catchThrowable(
                    () -> launcher.run(new ContentWorkflowInput(SPU_ID, LISTING_ID, plan)));
            assertThat(thrown).isInstanceOf(WorkflowException.class);
            assertThat(messagesOf(thrown)).contains(ContentPlan.MEDIA_PROCESS);
            assertThat(messagesOf(thrown)).isNotBlank();
            // 前面几步已落库的降级留痕仍在——这正是重跑必须清掉的陈旧信号
            assertThat(listing(store.get(SPU_ID).orElseThrow()).degradedSteps()).hasSize(2);

            // 端点恢复后重跑同一 workflowId（前次 failed → 放行）
            stubLlmSuccess();
            stubMediaSuccess();
            ContentWorkflowResult second = launcher.run(new ContentWorkflowInput(SPU_ID, LISTING_ID, plan));

            assertThat(second.runs())
                    .as("重跑步态（outcomes=%s）",
                            second.runs().stream().map(ContentStepRun::outcome).toList())
                    .allMatch(r -> r.outcome() == StepOutcome.OK);
            assertThat(second.degradedStepIds()).isEmpty();
            Listing listing = listing(store.get(SPU_ID).orElseThrow());
            assertThat(listing.degradedSteps()).isEmpty();
            assertThat(listing.titleOverrides()).containsKey("zh-CN");
        } finally {
            env.close();
        }
    }

    // ---------------- 场景 4：硬依赖失败 → workflow failed ----------------

    @Test
    void hardDependencyFailure_failsWorkflow() throws Exception {
        stubLlmServerError();
        seedMasterReadyListing();

        TestWorkflowEnvironment env = newEnvironment();
        try {
            Throwable thrown = catchThrowable(() -> launcher(env).run(
                    new ContentWorkflowInput(SPU_ID, LISTING_ID, ContentPlan.standard())));

            assertThat(thrown).isInstanceOf(WorkflowException.class);
            assertThat(messagesOf(thrown)).contains(ContentPlan.I18N_BACKFILL);
        } finally {
            env.close();
        }
    }

    // ---------------- harness ----------------

    /**
     * 造"master 就绪 + 待就绪 Listing"：复用 #19 采集入口（SPI 装配 1688 Adapter）→ ingest →
     * 把媒体源指向本地 fake 端点 → 装配 Listing 落库。这就是内容链的输入形态。
     */
    private void seedMasterReadyListing() throws Exception {
        PlatformAdapterProvider adapter = ServiceLoader.load(PlatformAdapterProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> "1688".equals(p.platform()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "classpath 未发现 1688 adapter provider（adapter-1688 是否在 test classpath?）"));
        OfferFetchCapability offerFetch = adapter.getCapability(OfferFetchCapability.class);

        SourceRef sourceRef = new SourceRef("1688", "6688990011",
                "http://localhost:" + server.port() + OFFER_ENDPOINT, "2026-09-10T00:00:00Z");
        new CatalogIngestService(offerFetch, store).ingest(sourceRef);
        assertThat(store.get(SPU_ID)).as("采集应落库 master").isPresent();

        ProductCatalog reachable = withLocalMedia(store.get(SPU_ID).orElseThrow());
        store.put(reachable);

        ProductCatalog drafted = new ListingDraftFactory(Clock.systemUTC()).draft(
                reachable, CHANNEL_ID, new CategoryRef("taobao", "5001", "数码/影音"), List.of("zh-CN", "en"));
        store.put(drafted);
        assertThat(listing(store.get(SPU_ID).orElseThrow()).listingId()).isEqualTo(LISTING_ID);
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

    /** 起一个 TestWorkflowEnvironment：真实 Step + 真实 OpenAI-compatible provider（打到 WireMock）。 */
    private TestWorkflowEnvironment newEnvironment() {
        ContentAiStepProvider provider = ContentAiStepProvider.of(
                Map.of("llm.base_url", "http://localhost:" + server.port() + "/v1",
                        "llm.model", "fake-model",
                        "llm.api_key", "test-key"),
                mediaRoot);
        ContentChainActivities activities = new ContentChainActivitiesImpl(store,
                new ContentStepExecutor(provider.steps(), Clock.systemUTC()));

        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        ContentWorkerFactory.register(env.newWorker(ContentRuntime.TASK_QUEUE), activities);
        if (!env.isStarted()) {
            env.start();
        }
        return env;
    }

    private ContentWorkflowLauncher launcher(TestWorkflowEnvironment env) {
        return new ContentWorkflowLauncher(env.getWorkflowClient(), ContentRuntime.TASK_QUEUE);
    }

    /** 已到达 fake LLM 端点的 chat/completions 请求数——用来证明"这次到底有没有真的调模型"。 */
    private int chatCalls() {
        return (int) server.getAllServeEvents().stream()
                .filter(event -> CHAT_ENDPOINT.equals(event.getRequest().getUrl()))
                .count();
    }

    /**
     * LLM 端点成功桩。**用显式 priority 而非插入顺序**：{@link #stubLlmServerError()} 与它同 URL，
     * 靠"后加的赢"是隐式契约，WireMock 的匹配次序不保证跨版本稳定——优先级才是可读的确定性。
     */
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

    /** 媒体源 500（制造硬依赖 media.process 失败）。 */
    private void stubMediaServerError() {
        server.stubFor(get(urlPathMatching("/media/.*")).atPriority(2)
                .willReturn(aResponse().withStatus(500)));
    }

    /** 媒体源 200（最高优先级；端点恢复后重跑用）。 */
    private void stubMediaSuccess() {
        server.stubFor(get(urlPathMatching("/media/.*")).atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "image/jpeg")
                        .withBody(new byte[] {1, 2, 3, 4})));
    }

    private static ContentStepRun step(ContentWorkflowResult result, String stepId) {
        return result.runs().stream().filter(r -> r.stepId().equals(stepId)).findFirst()
                .orElseThrow(() -> new AssertionError("执行记录缺步骤: " + stepId));
    }

    private static Listing listing(ProductCatalog doc) {
        return doc.listings().stream().filter(l -> l.listingId().equals(LISTING_ID)).findFirst()
                .orElseThrow(() -> new AssertionError("文档内无 Listing: " + LISTING_ID));
    }

    /** 汇总异常链上的全部消息（Temporal 会把业务异常包进 ApplicationFailure / WorkflowFailedException）。 */
    private static String messagesOf(Throwable thrown) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            sb.append(cause.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    private static String offerFixture() throws Exception {
        try (InputStream in = ContentWorkflowE2ETest.class
                .getResourceAsStream("/fixtures/1688-offer-demo.json")) {
            if (in == null) {
                throw new AssertionError("demo fixture 缺失: /fixtures/1688-offer-demo.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
