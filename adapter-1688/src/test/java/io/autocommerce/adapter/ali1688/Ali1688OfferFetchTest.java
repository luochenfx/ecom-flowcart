package io.autocommerce.adapter.ali1688;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.contract.AdapterErrorKind;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.OfferData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ali1688OfferFetch HTTP 层测试（WireMock 模拟 1688 网关，无真实账号可跑）。
 *
 * <p>验证：POST form（productId）拉取 → OfferData；HTTP 429/5xx → RETRYABLE、
 * 4xx → NON_RETRYABLE、业务拒绝（success=false）→ NON_RETRYABLE（Testing §6 错误纪律）。
 */
class Ali1688OfferFetchTest {

    private static final String PATH = "/openapi/param2/1/com.alibaba.product/alibaba.product.get";
    private static WireMockServer server;
    private static final Ali1688OfferFetch fetch = new Ali1688OfferFetch();

    @BeforeAll
    static void startServer() throws Exception {
        server = new WireMockServer(options().dynamicPort());
        server.start();

        String okBody = fixture();
        server.stubFor(post(urlEqualTo(PATH + "?case=ok"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(okBody)));
        server.stubFor(post(urlEqualTo(PATH + "?case=rate-limited"))
                .willReturn(aResponse().withStatus(429).withBody("rate limited")));
        server.stubFor(post(urlEqualTo(PATH + "?case=rate-limited-window"))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Retry-After", "5")
                        .withBody("rate limited")));
        server.stubFor(post(urlEqualTo(PATH + "?case=server-error"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        server.stubFor(post(urlEqualTo(PATH + "?case=bad-request"))
                .willReturn(aResponse().withStatus(400).withBody("bad param")));
        server.stubFor(post(urlEqualTo(PATH + "?case=business-error"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"errorCode\":\"isv.invalid-parameter\","
                                + "\"errorMsg\":\"商品不存在或已删除\"}")));
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void fetchesOfferByPostingProductId() {
        OfferData offer = fetch.fetchOffer(sourceRef("?case=ok", "6688990011"));

        assertThat(offer.externalId()).isEqualTo("6688990011");
        assertThat(offer.title()).isEqualTo("便携蓝牙音箱 迷你无线低音炮 户外防水");
        assertThat(offer.skus()).hasSize(2);
        assertThat(offer.raw()).isNotNull();
    }

    @Test
    void http429IsRetryable() {
        assertThatThrownBy(() -> fetch.fetchOffer(sourceRef("?case=rate-limited", "1")))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("429");
                });
    }

    @Test
    void http429CarriesRetryAfterWindow() {
        // specs/0005 §6：429 带 Retry-After 头 → retryable_after 供退避参考
        assertThatThrownBy(() -> fetch.fetchOffer(sourceRef("?case=rate-limited-window", "1")))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.RETRYABLE);
                    assertThat(e.retryableAfter()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    void http5xxIsRetryable() {
        assertThatThrownBy(() -> fetch.fetchOffer(sourceRef("?case=server-error", "1")))
                .isInstanceOfSatisfying(AdapterException.class, e ->
                        assertThat(e.kind()).isEqualTo(AdapterErrorKind.RETRYABLE));
    }

    @Test
    void http4xxIsNonRetryable() {
        assertThatThrownBy(() -> fetch.fetchOffer(sourceRef("?case=bad-request", "1")))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("400");
                });
    }

    @Test
    void businessRejectionIsNonRetryable() {
        assertThatThrownBy(() -> fetch.fetchOffer(sourceRef("?case=business-error", "1")))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("isv.invalid-parameter");
                });
    }

    @Test
    void missingUrlIsNonRetryable() {
        SourceRef ref = new SourceRef("1688", "6688990011", null, "2026-09-10T00:00:00Z");
        assertThatThrownBy(() -> fetch.fetchOffer(ref))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("missing-url");
                });
    }

    private static SourceRef sourceRef(String suffix, String externalId) {
        return new SourceRef("1688", externalId,
                "http://localhost:" + server.port() + PATH + suffix, "2026-09-10T00:00:00Z");
    }

    private static String fixture() throws Exception {
        try (InputStream in = Ali1688OfferFetchTest.class
                .getResourceAsStream("/fixtures/1688-offer-response.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
