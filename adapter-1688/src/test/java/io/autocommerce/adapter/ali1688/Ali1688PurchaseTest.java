package io.autocommerce.adapter.ali1688;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import io.autocommerce.core.contract.AdapterErrorKind;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.LogisticsTrace;
import io.autocommerce.core.contract.dto.PurchaseResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ali1688Purchase HTTP 层测试（WireMock 模拟 1688 网关，<b>无真实账号可跑</b>）。
 *
 * <p>覆盖四段能力的请求形态（端点路径 / 应用级参数 / 系统参数与签名）与
 * <b>三类错误映射</b>（specs/0005 §6、Testing §6）：
 * <ul>
 *   <li>限流 HTTP 429 → RETRYABLE（带 Retry-After 窗口）；</li>
 *   <li>业务拒绝 {@code success=false} → NON_RETRYABLE（例外：{@code 500*} 平台侧 → RETRYABLE）；</li>
 *   <li><b>写操作</b>连接断开 → AMBIGUOUS（在途未知，不重发、走 reconcile）；
 *       同一故障对<b>读</b>操作（物流查询）→ RETRYABLE。</li>
 * </ul>
 *
 * <p>用例分流：网关 URL 固定（param2 路径），故按请求体里的用例标识
 * （{@code specId} / {@code tradeID} / {@code orderId} 取值为 {@code case-*}）分派不同响应。
 */
class Ali1688PurchaseTest {

    private static final String CREATE_PATH =
            Ali1688Api.TRADE_FAST_CREATE_ORDER.path(Ali1688TradeSamples.APP_KEY);
    private static final String CANCEL_PATH =
            Ali1688Api.TRADE_CANCEL.path(Ali1688TradeSamples.APP_KEY);
    private static final String PAY_PATH =
            Ali1688Api.TRADE_PROTOCOL_PAY_PREPARE.path(Ali1688TradeSamples.APP_KEY);
    private static final String LOGISTICS_PATH =
            Ali1688Api.LOGISTICS_TRACE_BUYER_VIEW.path(Ali1688TradeSamples.APP_KEY);

    private static final String CASE_OK = "case-ok";
    private static final String CASE_RATE_LIMITED = "case-rate-limited";
    private static final String CASE_BUSINESS_ERROR = "case-business-error";
    private static final String CASE_PLATFORM_ERROR = "case-platform-error";
    private static final String CASE_CONNECTION_RESET = "case-connection-reset";
    private static final String CASE_SERVICE_UNAVAILABLE = "case-service-unavailable";
    private static final String CASE_TP_EXCEPTION = "case-tp-exception";
    private static final String CASE_TOO_MANY_REQUESTS = "case-too-many-requests";

    private static WireMockServer server;
    private static Ali1688Purchase purchase;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();

        stub(CREATE_PATH, CASE_OK, aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(Ali1688TradeSamples.fixture("1688-trade-fastCreateOrder-response.json")));
        stub(CANCEL_PATH, CASE_OK, aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(Ali1688TradeSamples.fixture("1688-trade-cancel-response.json")));
        stub(PAY_PATH, CASE_OK, aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(Ali1688TradeSamples.fixture("1688-trade-preparePay-response.json")));
        stub(LOGISTICS_PATH, CASE_OK, aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(Ali1688TradeSamples.fixture("1688-logistics-trace-response.json")));

        // —— 三类错误：限流 / 业务拒绝 / 连接断开 ——
        for (String path : new String[] {CREATE_PATH, CANCEL_PATH, PAY_PATH, LOGISTICS_PATH}) {
            stub(path, CASE_RATE_LIMITED, aResponse().withStatus(429)
                    .withHeader("Retry-After", "5").withBody("rate limited"));
            stub(path, CASE_BUSINESS_ERROR, aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"success\":false,\"code\":\"400\","
                            + "\"message\":\"OfferId and quantity is required\"}"));
            stub(path, CASE_PLATFORM_ERROR, aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"success\":false,\"code\":\"500\","
                            + "\"message\":\"view order service error\"}"));
            stub(path, CASE_CONNECTION_RESET, aResponse().withFault(Fault.EMPTY_RESPONSE));
        }

        // —— classify() 识别但 README 此前漏列的三项：SERVICE_UNAVAILABLE / TP_EXCEPTION /
        // TOO_MANY_REQUESTS 也属平台侧临时故障 → RETRYABLE（与 500* / *SYSTEM_ERROR 同档）。
        // 用 case 名经 cargoParamList.specId 分流，参数化测试逐一断言。 ——
        stub(CREATE_PATH, CASE_SERVICE_UNAVAILABLE, aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":false,\"code\":\"SERVICE_UNAVAILABLE\","
                        + "\"message\":\"service is temporarily unavailable\"}"));
        stub(CREATE_PATH, CASE_TP_EXCEPTION, aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":false,\"code\":\"TP_EXCEPTION\","
                        + "\"message\":\"third-party service exception\"}"));
        stub(CREATE_PATH, CASE_TOO_MANY_REQUESTS, aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":false,\"code\":\"TOO_MANY_REQUESTS\","
                        + "\"message\":\"too many requests\"}"));

        purchase = newPurchase();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    // ————————————————— 下单 —————————————————

    @Test
    void createPurchasePostsSignedCargoAndAddress() {
        PurchaseResult result = purchase.createPurchase(Ali1688TradeSamples.draft(CASE_OK));

        assertThat(result.platformPurchaseNo()).isEqualTo(Ali1688TradeSamples.PURCHASE_NO);

        Map<String, String> form = lastForm(CREATE_PATH);
        assertThat(form.get("flow")).isEqualTo("saleproxy");
        assertThat(form.get("cargoParamList")).contains("\"specId\":\"" + CASE_OK + "\"");
        assertThat(form.get("addressParam")).contains("\"fullName\":\"张三\"");
        // 系统参数与签名：access_token / 时间戳 / 大写 HEX 签名（_aop_signature 不参与自身签名）
        assertThat(form.get("access_token")).isEqualTo(Ali1688TradeSamples.ACCESS_TOKEN);
        assertThat(form).containsKey("_aop_timestamp");
        assertThat(form.get("_aop_signature")).matches("[0-9A-F]{40}");
    }

    @Test
    void createPurchaseRateLimitedIsRetryableWithWindow() {
        assertThatThrownBy(() -> purchase.createPurchase(Ali1688TradeSamples.draft(CASE_RATE_LIMITED)))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("429");
                    assertThat(e.retryableAfter()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    void createPurchaseBusinessRejectionIsNonRetryable() {
        assertThatThrownBy(() -> purchase.createPurchase(
                        Ali1688TradeSamples.draft(CASE_BUSINESS_ERROR)))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("400");
                    assertThat(e.getMessage()).contains("OfferId and quantity is required");
                });
    }

    /**
     * 官方错误码平台侧临时故障 → RETRYABLE（不是业务拒绝）。
     * 涵盖 {@code 500*} / {@code *SYSTEM_ERROR*}（含 {@code SYSTEM_BUSY}）以及 README 漏列后补齐的
     * {@code SERVICE_UNAVAILABLE} / {@code TP_EXCEPTION} / {@code TOO_MANY_REQUESTS}——
     * 一并由 {@code Ali1688Gateway.classify()} 识别为 RETRYABLE。参数化钉齐代码 ↔ 文档 ↔ 测试，
     * PR #42 review 前 {@code 500} 单独一个测试、其余三项完全无覆盖。
     */
    @ParameterizedTest
    @MethodSource("platformSideErrorCodes")
    void platformSideErrorCodesAreRetryable(String caseId, String expectedCode) {
        assertThatThrownBy(() -> purchase.createPurchase(Ali1688TradeSamples.draft(caseId)))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo(expectedCode);
                });
    }

    private static Stream<Arguments> platformSideErrorCodes() {
        return Stream.of(
                Arguments.of(CASE_PLATFORM_ERROR, "500"),
                Arguments.of(CASE_SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE"),
                Arguments.of(CASE_TP_EXCEPTION, "TP_EXCEPTION"),
                Arguments.of(CASE_TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS"));
    }

    @Test
    void createPurchaseConnectionResetIsAmbiguous() {
        // 写操作在途未知：不重发，交 reconcile
        assertThatThrownBy(() -> purchase.createPurchase(
                        Ali1688TradeSamples.draft(CASE_CONNECTION_RESET)))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.AMBIGUOUS);
                    assertThat(e.platformCode()).isEqualTo("network");
                });
    }

    // ————————————————— 取消 / 支付 / 物流 —————————————————

    @Test
    void cancelPurchasePostsTradeIdAndFixedReason() {
        purchase.cancelPurchase(CASE_OK);

        Map<String, String> form = lastForm(CANCEL_PATH);
        assertThat(form.get("tradeID")).isEqualTo(CASE_OK);
        assertThat(form.get("cancelReason")).isEqualTo("other");
        assertThat(form.get("webSite")).isEqualTo("1688");
        assertThat(form.get("_aop_signature")).matches("[0-9A-F]{40}");
    }

    @Test
    void payPurchasePostsWithholdParam() {
        purchase.payPurchase(CASE_OK);

        Map<String, String> form = lastForm(PAY_PATH);
        assertThat(form.get("tradeWithholdPreparePayParam"))
                .isEqualTo("{\"orderId\":\"" + CASE_OK + "\"}");
    }

    @Test
    void fetchLogisticsMapsBuyerViewTrace() {
        LogisticsTrace trace = purchase.fetchLogistics(CASE_OK);

        assertThat(trace.platformPurchaseNo()).isEqualTo(CASE_OK);
        assertThat(trace.tracking()).hasSize(1);
        assertThat(trace.tracking().get(0).trackingNo()).isEqualTo("3832890717253");
        assertThat(lastForm(LOGISTICS_PATH).get("orderId")).isEqualTo(CASE_OK);
    }

    @Test
    void readOperationConnectionResetIsRetryable() {
        // 物流查询是读操作：同样的连接断开，重试安全 → RETRYABLE（不掩饰成 AMBIGUOUS）
        assertThatThrownBy(() -> purchase.fetchLogistics(CASE_CONNECTION_RESET))
                .isInstanceOfSatisfying(AdapterException.class, e ->
                        assertThat(e.kind()).isEqualTo(AdapterErrorKind.RETRYABLE));
    }

    // ————————————————— 配置与入参 —————————————————

    @Test
    void missingCredentialIsNonRetryable() {
        Ali1688Purchase unconfigured = new Ali1688Purchase(Ali1688AdapterConfig.unconfigured());

        assertThatThrownBy(() -> unconfigured.createPurchase(Ali1688TradeSamples.draft(CASE_OK)))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("missing-credential");
                });
    }

    @Test
    void blankPurchaseNoIsNonRetryable() {
        assertThatThrownBy(() -> purchase.cancelPurchase("  "))
                .isInstanceOfSatisfying(AdapterException.class, e ->
                        assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE));
        assertThatThrownBy(() -> purchase.fetchLogistics(null))
                .isInstanceOfSatisfying(AdapterException.class, e ->
                        assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE));
    }

    // ————————————————— helpers —————————————————

    private static void stub(String path, String caseId, ResponseDefinitionBuilder response) {
        server.stubFor(post(urlPathEqualTo(path))
                .withRequestBody(containing(caseId))
                .willReturn(response));
    }

    private static Ali1688Purchase newPurchase() {
        Ali1688AdapterConfig config = Ali1688AdapterConfig.of(Ali1688TradeSamples.credential(),
                "http://localhost:" + server.port());
        // 令牌桶放宽（不拖慢测试）+ 固定时间戳（签名可复现）
        return new Ali1688Purchase(new Ali1688Gateway(config, HttpClient.newHttpClient(),
                new Ali1688RateLimiter(1_000.0d, 1_000), new ObjectMapper(),
                () -> 1_700_000_000_000L));
    }

    private static Map<String, String> lastForm(String path) {
        return Ali1688TradeSamples.formOf(server.findAll(postRequestedFor(urlPathEqualTo(path)))
                .stream()
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("没有捕获到请求: " + path))
                .getBodyAsString());
    }
}
