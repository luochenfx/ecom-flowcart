package io.autocommerce.adapter.ali1688;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.autocommerce.core.contract.AdapterErrorKind;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.CredentialView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ali1688Auth：OAuth2.0 换票（{@code system.oauth2/getToken}）测试（WireMock，无真实账号可跑）。
 *
 * <p>验证：换票出参 → 新 {@link CredentialView}（含新 access_token / refresh_token /
 * expires_at）；<b>该端点不签名</b>（官方：getToken 不需要签名）；换票被拒 = NON_RETRYABLE；
 * 平台 5xx = RETRYABLE；长期 token 形态（无 refresh_token）无法自动刷新。
 */
class Ali1688AuthTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final String CASE_OK = "case-ok";
    private static final String CASE_INVALID_GRANT = "case-invalid-grant";
    private static final String CASE_SERVER_ERROR = "case-server-error";

    private static WireMockServer server;
    private static Ali1688Auth auth;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();

        server.stubFor(post(urlPathEqualTo(Ali1688Auth.AUTH_PATH))
                .withRequestBody(containing(CASE_OK))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Ali1688TradeSamples.fixture("1688-oauth-getToken-response.json"))));
        server.stubFor(post(urlPathEqualTo(Ali1688Auth.AUTH_PATH))
                .withRequestBody(containing(CASE_INVALID_GRANT))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_grant\","
                                + "\"error_description\":\"refresh token expired\"}")));
        server.stubFor(post(urlPathEqualTo(Ali1688Auth.AUTH_PATH))
                .withRequestBody(containing(CASE_SERVER_ERROR))
                .willReturn(aResponse().withStatus(502).withBody("bad gateway")));

        auth = new Ali1688Auth(
                Ali1688AdapterConfig.of(Ali1688TradeSamples.credential(),
                        "http://localhost:" + server.port()),
                HttpClient.newHttpClient(), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void refreshExchangesRefreshTokenForNewCredentialView() {
        CredentialView refreshed = auth.refresh(view(CASE_OK));

        assertThat(refreshed.type()).isEqualTo(Ali1688Credential.TYPE);
        assertThat(refreshed.secrets().get("access_token")).isEqualTo("new-access-token");
        assertThat(refreshed.secrets().get("refresh_token")).isEqualTo("new-refresh-token");
        // app_key / secret 原样带回（Channel 域依此回封密文）
        assertThat(refreshed.secrets().get("app_key")).isEqualTo(Ali1688TradeSamples.APP_KEY);
        assertThat(refreshed.secrets().get("secret")).isEqualTo(Ali1688TradeSamples.APP_SECRET);
        // expires_in=36000s → 固定时钟 +10h
        assertThat(refreshed.expiresAt()).isEqualTo("2026-09-10T10:00:00Z");
    }

    @Test
    void tokenEndpointIsNotSigned() {
        auth.refresh(view(CASE_OK));

        Map<String, String> form = Ali1688TradeSamples.formOf(
                server.findAll(postRequestedFor(urlPathEqualTo(Ali1688Auth.AUTH_PATH))).stream()
                        .reduce((first, second) -> second).orElseThrow().getBodyAsString());
        assertThat(form.get("grant_type")).isEqualTo("refresh_token");
        assertThat(form.get("client_id")).isEqualTo(Ali1688TradeSamples.APP_KEY);
        assertThat(form.get("client_secret")).isEqualTo(Ali1688TradeSamples.APP_SECRET);
        assertThat(form.get("refresh_token")).isEqualTo(CASE_OK);
        // 官方明确「调用 getToken 接口不需要签名」
        assertThat(form).doesNotContainKey("_aop_signature").doesNotContainKey("_aop_timestamp");
        assertThat(form).doesNotContainKey("access_token");
    }

    @Test
    void rejectedRefreshIsNonRetryable() {
        assertThatThrownBy(() -> auth.refresh(view(CASE_INVALID_GRANT)))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("invalid_grant");
                    assertThat(e.getMessage()).contains("refresh token expired");
                });
    }

    @Test
    void platformFailureIsRetryable() {
        assertThatThrownBy(() -> auth.refresh(view(CASE_SERVER_ERROR)))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("502");
                });
    }

    @Test
    void longLivedTokenWithoutRefreshTokenCannotRefresh() {
        CredentialView longLived = new CredentialView(Ali1688Credential.TYPE,
                Map.of("app_key", Ali1688TradeSamples.APP_KEY, "secret",
                        Ali1688TradeSamples.APP_SECRET, "access_token",
                        Ali1688TradeSamples.ACCESS_TOKEN),
                null);

        assertThatThrownBy(() -> auth.refresh(longLived))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("missing-refresh-token");
                });
    }

    private static CredentialView view(String refreshToken) {
        return Ali1688TradeSamples.credential()
                .toCredentialView(null, Ali1688TradeSamples.ACCESS_TOKEN, refreshToken);
    }
}
