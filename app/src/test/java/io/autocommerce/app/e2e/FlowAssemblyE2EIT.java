package io.autocommerce.app.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.autocommerce.adapter.fake.FakeSalesAdapterProvider;
import io.autocommerce.adapter.fake.FakeSalesPublish;
import io.autocommerce.adapterhost.AdapterHost;
import io.autocommerce.app.FlowcartApplication;
import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.catalog.store.PostgresCatalogStore;
import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.ProductCatalog;
import io.autocommerce.core.contract.PublishCapability;
import io.autocommerce.publish.PublishStateStore;
import io.autocommerce.publish.PublishStatus;
import io.autocommerce.worker.content.ContentRuntime;
import io.autocommerce.worker.flow.ListingFlowRuntime;
import io.autocommerce.worker.publish.PublishRuntime;
import io.autocommerce.worker.publish.PublishWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端到端 e2e 验收（#75 / specs/0007 §9.2/§9.3/§10 步 8）：<b>真链路</b>——连真 Temporal server +
 * 真 Postgres，经 REST 边界（{@code POST /api/v1/ingest} → 编排链 → {@code GET /api/v1/flows/{id}}）
 * 覆盖四条路径。以 {@code mvn clean verify -Pe2e} 运行（profile 见根 pom；默认 {@code mvn clean test}
 * 不执行本类——类名以 {@code IT} 结尾，落在 surefire 默认匹配之外，failsafe 仅在 e2e profile 内绑定）。
 *
 * <p><b>与既有 {@code *E2ETest} 的区别（本票的增量）</b>：{@code worker-runtime} 的 5 个
 * {@code *E2ETest} 全用 in-process {@code TestWorkflowEnvironment} + 内存 store——它们验证的是
 * <i>业务语义</i>；本类验证的是<i>生产装配 + 真基础设施</i>：
 * <ul>
 *   <li>不 import {@code AppTemporalTestConfiguration}，Temporal 走生产 {@code TemporalConfiguration}
 *       （真 {@code localhost:7233}）；</li>
 *   <li>不关 Flyway（真迁移 {@code V1__catalog.sql}），{@code CatalogStore} = 真 {@code PostgresCatalogStore}
 *       （{@code localhost:5433}）；</li>
 *   <li>经 REST 边界进入（{@code FlowController} 在 {@code io.autocommerce.api}，由
 *       {@code scanBasePackages="io.autocommerce"} 可达）；</li>
 *   <li>四个 worker（flow / content / publish / order）按 {@code app.role=api,worker,scheduler} 真启动。</li>
 * </ul>
 *
 * <p><b>外部边界</b>由固定端口的 WireMock 冒充（{@link #STUB_PORT}，与根 pom e2e profile 的
 * {@code FLOWCART_LLM_BASE_URL} 一致）：1688 offer 端点（{@code source_ref.url} 直连模式，无需真凭据）、
 * OpenAI-compatible LLM 端点、媒体源。铺货平台由 {@code adapter-fake}（走真 SPI + {@code AdapterHost}
 * 装配）替代。
 *
 * <p><b>隔离 / 可重复策略（AC-12）</b>：坐标一律经单一事实源推导（{@code ListingFlowRuntime} /
 * {@code ContentRuntime} / {@code PublishRuntime}），且每次 JVM 运行注入一次性 {@link #RUN_TAG}、
 * 每个用例再用路径后缀——故同一 {@code (spuId, channelId)} 不会跨用例 / 跨运行复用，无需清理真库 /
 * Temporal 残留即可重复执行（不因残留假绿，也不随机红）。四个用例共享一个 Spring 上下文（有状态
 * {@code fake-sales} 能力单例）——只有路径二（{@code @Order(4)}，最后跑）会脚本化能力结局，且每次
 * {@code add} 恰消费一条脚本，故其余用例恒落 {@code PUBLISHED} 兜底；计数断言取"增量"而非绝对值。
 */
@SpringBootTest(classes = FlowcartApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 真 Postgres（compose 宿主 5433 → 容器 5432）；真 Temporal server（宿主 7233）。
                "spring.datasource.url=jdbc:postgresql://localhost:5433/flowcart",
                "spring.datasource.username=flowcart",
                "spring.datasource.password=flowcart",
                // 刻意**不关** Flyway：启动即真迁移 V1__catalog.sql（AC-11a 证据之一）。
                "spring.flyway.enabled=true",
                "app.temporal.target=localhost:7233",
                "app.temporal.namespace=default",
                "app.role=api,worker,scheduler",
                "app.data-root=target/e2e-data",
                "app.media-root=target/e2e-media",
                "app.address-key=0123456789abcdef0123456789abcdef"
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlowAssemblyE2EIT {

    /**
     * 固定 stub 端口：须与根 pom {@code e2e} profile 的 {@code FLOWCART_LLM_BASE_URL} 一致
     * （LLM 端点在<b>装配期</b>从 env 读，测试 JVM 无法改 env）。取高位、非 common，降低冲突概率。
     */
    private static final int STUB_PORT = 41889;
    private static final String LLM_BASE_URL = "http://localhost:" + STUB_PORT + "/v1";
    private static final String OFFER_PATH = "/openapi/param2/1/com.alibaba.product/alibaba.product.get";
    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String MEDIA_PATH = "/media/e2e-demo.jpg";
    private static final String FETCHED_AT = "2026-09-10T00:00:00Z";

    /** 每次 JVM 运行一次性标识：使坐标跨运行不重合（可重复运行的关键，见类 javadoc）。 */
    private static final String RUN_TAG = Long.toString(System.nanoTime(), 36);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static WireMockServer stub;
    private static HttpClient http;

    @LocalServerPort
    private int port;

    @Autowired
    private WorkflowClient client;

    @Autowired
    private AdapterHost adapterHost;

    @Autowired
    private CatalogStore catalogStore;

    @Autowired
    private PublishStateStore publishStateStore;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------------- 生命周期 ----------------

    @BeforeAll
    static void startStub() {
        assertThat(System.getenv("FLOWCART_LLM_BASE_URL"))
                .as("e2e 须经 failsafe <environmentVariables> 注入 FLOWCART_LLM_BASE_URL"
                        + "（缺失 / 不符说明 e2e profile 未生效）")
                .isEqualTo(LLM_BASE_URL);
        stub = new WireMockServer(options().port(STUB_PORT));
        try {
            stub.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException("e2e stub 无法绑定固定端口 " + STUB_PORT
                    + "（被占用？）——该端口须与根 pom e2e profile 的 FLOWCART_LLM_BASE_URL 一致", e);
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop();
        }
    }

    @BeforeEach
    void resetStubs() {
        stub.resetAll();
        stubOfferOk();
        stubMediaOk();
    }

    // ---------------- 路径一：正常收敛 ----------------

    @Test
    @Order(1)
    void path1_normalConvergence_publishesEndToEnd() throws Exception {
        stubLlmSuccess();
        String externalId = externalId("p1");
        String channelId = channelId("p1");

        JsonNode accepted = ingest(externalId, channelId, "cross_border", List.of("zh-CN", "en"), 202);
        String spuId = accepted.get("spu_id").asText();
        String listingId = accepted.get("listing_id").asText();
        String flowId = accepted.get("flow_workflow_id").asText();

        // 202 坐标 = 确定性推导（单一事实源），非自造字面量
        assertThat(spuId).as("spuId 确定性推导 = spu-{platform}-{external_id}")
                .isEqualTo("spu-1688-" + externalId);
        assertThat(listingId).isEqualTo(PublishRuntime.workflowIdFor(spuId, channelId));
        assertThat(flowId).isEqualTo(ListingFlowRuntime.workflowIdFor(spuId, channelId));

        JsonNode flow = awaitFlowStatus(flowId, "COMPLETED", Duration.ofSeconds(120));
        assertThat(flow.get("published").asBoolean()).isTrue();
        assertThat(flow.get("content_ready").asBoolean()).isTrue();
        assertThat(flow.get("platform_item_id").asText()).isNotBlank();
        assertThat(flow.get("degraded_steps")).isEmpty();

        // AC-7：真库落库事实（catalog 在 Postgres；铺货状态在 JSON 投影）
        assertThat(catalogStore).as("CatalogStore = 生产 Postgres 实现（非内存替身）")
                .isInstanceOf(PostgresCatalogStore.class);
        ProductCatalog doc = catalogStore.get(spuId).orElseThrow();
        Listing listing = doc.listings().stream()
                .filter(l -> listingId.equals(l.listingId())).findFirst().orElseThrow();
        assertThat(listing.spuId()).isEqualTo(spuId);
        // 直查 Postgres（绕过装配），证明确实落到了真库而非文件 / 内存
        Integer rows = jdbc.queryForObject(
                "select count(*) from catalog_product where spu_id = ?", Integer.class, spuId);
        assertThat(rows).as("catalog master 文档真落 Postgres").isEqualTo(1);
        assertThat(publishStateStore.get(listingId).orElseThrow().status()).isEqualTo(PublishStatus.PUBLISHED);

        // AC-8：三链坐标口径一致（fulfillment- / content- / listing- 指向同一 Listing）
        assertThat(flow.get("listing_id").asText()).isEqualTo(listingId);
        assertThat(ContentRuntime.workflowIdFor(listingId)).isEqualTo("content-" + listingId);
        assertThat(describeExists(ContentRuntime.workflowIdFor(listingId)))
                .as("内容链子 workflow 真实存在于 Temporal: " + ContentRuntime.workflowIdFor(listingId)).isTrue();
        assertThat(PublishRuntime.workflowIdFor(spuId, channelId)).isEqualTo(listingId);
        assertThat(describeExists(PublishRuntime.workflowIdFor(spuId, channelId)))
                .as("铺货链子 workflow 真实存在于 Temporal: " + listingId).isTrue();
    }

    // ---------------- 路径四：采集失败（可重试）→ 503 + 无 zombie 链 ----------------

    @Test
    @Order(2)
    void path4_offerFetchRetryable_returns503_withNoZombieFlowChain() throws Exception {
        stubOfferServerError(); // offer 端点 5xx → AdapterException(RETRYABLE) → HTTP 503
        String externalId = externalId("p4");
        String channelId = channelId("p4");

        JsonNode error = ingest(externalId, channelId, "domestic", List.of("zh-CN"), 503);
        assertThat(error.get("code").asText()).isEqualTo("adapter_error");

        // 采集失败绝不启动编排链——反查确定性 workflowId 在 Temporal 不存在（无 zombie 链）
        String spuId = "spu-1688-" + externalId;
        String flowId = ListingFlowRuntime.workflowIdFor(spuId, channelId);
        assertThatThrownBy(() -> client.newUntypedWorkflowStub(flowId).describe())
                .as("采集失败绝不应启动编排链: " + flowId)
                .isInstanceOf(WorkflowNotFoundException.class);
        assertThatThrownBy(() -> client.newUntypedWorkflowStub(PublishRuntime.workflowIdFor(spuId, channelId))
                .describe())
                .as("采集失败绝不应启动铺货链")
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    // ---------------- 路径三：内容不达标（硬依赖 Step 失败）→ 编排链 failed，铺货链从未启动 ----------------

    @Test
    @Order(3)
    void path3_criticalContentStepFailure_failsFlow_andNeverStartsPublish() throws Exception {
        stubLlmServerError(); // 跨境链 i18n.backfill（critical）→ 内容子链 failed → 编排链 failed
        String externalId = externalId("p3");
        String channelId = channelId("p3");

        JsonNode accepted = ingest(externalId, channelId, "cross_border", List.of("zh-CN", "en"), 202);
        String spuId = accepted.get("spu_id").asText();
        String listingId = accepted.get("listing_id").asText();
        String flowId = accepted.get("flow_workflow_id").asText();

        JsonNode flow = awaitFlowStatus(flowId, "FAILED", Duration.ofSeconds(120));
        assertThat(flow.get("reason").asText()).isNotBlank();

        // 装配已完成（编排链第一步落库）：文档含该 Listing；但铺货链从未被启动
        ProductCatalog doc = catalogStore.get(spuId).orElseThrow();
        assertThat(doc.listings()).extracting(Listing::listingId).contains(listingId);
        assertThat(publishStateStore.get(listingId)).isEmpty();
        String publishId = PublishRuntime.workflowIdFor(spuId, channelId);
        assertThatThrownBy(() -> client.newUntypedWorkflowStub(publishId).describe())
                .as("内容不达标 ⇒ 铺货链绝不启动（且无 zombie 链）: " + publishId)
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    // ---------------- 路径二：AMBIGUOUS 挂起 → signal 裁定 → 收敛 ----------------
    // 放最后（@Order(4)）：本用例脚本化有状态能力（fake-sales），保证脚本污染不波及其它用例（见类 javadoc）。

    @Test
    @Order(4)
    void path2_ambiguousPublish_suspendsThenConfirmSignal_converges() throws Exception {
        stubLlmSuccess();
        String externalId = externalId("p2");
        String channelId = channelId("p2");

        FakeSalesPublish publish = (FakeSalesPublish) adapterHost.capability(
                FakeSalesAdapterProvider.PLATFORM, PublishCapability.class);
        int addCallsBefore = publish.addCalls().size();
        publish.script(FakeSalesPublish.Disposition.AMBIGUOUS);

        JsonNode accepted = ingest(externalId, channelId, "domestic", List.of("zh-CN"), 202);
        String spuId = accepted.get("spu_id").asText();
        String listingId = accepted.get("listing_id").asText();
        String flowId = accepted.get("flow_workflow_id").asText();

        // 铺货停在 AMBIGUOUS ⇒ 编排链同步挂起（Temporal 侧 RUNNING）
        awaitUntil(() -> publishStateStore.get(listingId)
                .map(s -> s.status() == PublishStatus.AMBIGUOUS).orElse(false), Duration.ofSeconds(120));
        assertThat(getFlow(flowId).get("status").asText()).isEqualTo("RUNNING");
        assertThat(publish.addCalls().size() - addCallsBefore)
                .as("歧义不自动重发：add 只发生一次").isEqualTo(1);

        // 裁定 signal 直发铺货链（编排链只是跟随者，不是信号路由器，specs/0007 §4.5）
        client.newWorkflowStub(PublishWorkflow.class, PublishRuntime.workflowIdFor(spuId, channelId))
                .confirmPublished("fake-item-human", "https://fake-sales.example.com/item/human");

        JsonNode flow = awaitFlowStatus(flowId, "COMPLETED", Duration.ofSeconds(120));
        assertThat(flow.get("published").asBoolean()).isTrue();
        assertThat(flow.get("platform_item_id").asText()).isEqualTo("fake-item-human");
        assertThat(publishStateStore.get(listingId).orElseThrow().status()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(publish.addCalls().size() - addCallsBefore)
                .as("人工确认回填不重复 add").isEqualTo(1);
    }

    // ---------------- harness ----------------

    /** {@code POST /api/v1/ingest}：期望状态码不符即失败并回显 body。 */
    private JsonNode ingest(String externalId, String channelId, String chain,
                            List<String> locales, int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/ingest"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        ingestBody(externalId, channelId, chain, locales), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode())
                .as("POST /api/v1/ingest 期望 %s，实得 %s，body=%s",
                        expectedStatus, response.statusCode(), response.body())
                .isEqualTo(expectedStatus);
        return JSON.readTree(response.body());
    }

    /** {@code GET /api/v1/flows/{workflowId}}，要求 200。 */
    private JsonNode getFlow(String workflowId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/flows/" + workflowId))
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode())
                .as("GET /api/v1/flows/%s 期望 200，实得 %s，body=%s",
                        workflowId, response.statusCode(), response.body())
                .isEqualTo(200);
        return JSON.readTree(response.body());
    }

    /** 轮询 {@code GET /flows/{id}} 直到状态达到期望。 */
    private JsonNode awaitFlowStatus(String workflowId, String expected, Duration timeout) throws Exception {
        JsonNode last = null;
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            last = getFlow(workflowId);
            if (expected.equals(last.path("status").asText())) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("等待 flow 状态超时：期望 " + expected + "，实际 " + last + "（" + workflowId + "）");
    }

    /** 反查 workflowId 在 Temporal 是否存在（真查服务端）。 */
    private boolean describeExists(String workflowId) {
        try {
            client.newUntypedWorkflowStub(workflowId).describe();
            return true;
        } catch (WorkflowNotFoundException e) {
            return false;
        }
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断", e);
            }
        }
        throw new AssertionError("等待条件超时（" + timeout.toSeconds() + "s）");
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static String externalId(String path) {
        return "e2e" + RUN_TAG + path;
    }

    private static String channelId(String path) {
        return "chan" + RUN_TAG + path;
    }

    private static String ingestBody(String externalId, String channelId, String chain, List<String> locales) {
        String localesJson = locales.stream().map(l -> "\"" + l + "\"").collect(Collectors.joining(","));
        return """
                {
                  "source_ref": {"platform":"1688","external_id":"%s",
                                 "url":"http://localhost:%d%s",
                                 "fetched_at":"%s"},
                  "channel_id": "%s",
                  "target_category": {"taxonomy":"taobao","value":"5001","label":"数码/影音"},
                  "locales": [%s],
                  "chain": "%s"
                }
                """.formatted(externalId, STUB_PORT, OFFER_PATH, FETCHED_AT, channelId, localesJson, chain);
    }

    // ---------------- stub 桩 ----------------

    private static void stubOfferOk() {
        stub.stubFor(post(urlEqualTo(OFFER_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(offerFixture())));
    }

    private static void stubOfferServerError() {
        stub.stubFor(post(urlEqualTo(OFFER_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"upstream down\"}")));
    }

    private static void stubMediaOk() {
        stub.stubFor(get(urlEqualTo(MEDIA_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "image/jpeg")
                .withBody(new byte[] {1, 2, 3, 4})));
    }

    private static void stubLlmSuccess() {
        stub.stubFor(post(urlEqualTo(CHAT_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"chatcmpl-e2e","object":"chat.completion","model":"e2e-model",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"E2E 内容产物"},
                                     "finish_reason":"stop"}],
                         "usage":{"prompt_tokens":10,"completion_tokens":6,"total_tokens":16}}
                        """)));
    }

    private static void stubLlmServerError() {
        stub.stubFor(post(urlEqualTo(CHAT_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"upstream down\"}}")));
    }

    /** 1688 offer fixture（imageUrl 指向本固定端口 stub，供内容链 media.process 下载）。 */
    private static String offerFixture() {
        try (InputStream in = FlowAssemblyE2EIT.class
                .getResourceAsStream("/fixtures/e2e-1688-offer.json")) {
            if (in == null) {
                throw new AssertionError("e2e offer fixture 缺失: /fixtures/e2e-1688-offer.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
