package io.autocommerce.adapter.ali1688;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.AuthCapability;
import io.autocommerce.core.contract.CredentialView;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1688 AuthCapability 实现（specs/0005 §5，#23）。
 *
 * <p>走官方 OAuth2.0 换票端点 {@code POST /auth/system.oauth2/getToken}
 * （{@code grant_type=refresh_token}）——官方明确「调用 getToken 接口不需要签名」，
 * 故本端点<b>不经 param2 签名网关</b>，也不用 {@code access_token}；凭据以
 * {@code client_id} / {@code client_secret} / {@code refresh_token} 形式直传 form。
 *
 * <p>入 / 出参都是 {@link CredentialView}（解密内存对象）：Adapter 无 AES 密钥，
 * 既不解密也不回封密文——回写密文由 Channel 域完成（§5 / §8）。本方法只负责
 * 「拿旧 refresh_token 换新 access_token，并算出新的过期时间」。
 *
 * <p>失败一律 NON_RETRYABLE / RETRYABLE（平台 5xx 与 429）：刷新是幂等读性质的换票，
 * 超时不按写操作 AMBIGUOUS 处理（无从"reconcile 一张令牌"）。
 */
public final class Ali1688Auth implements AuthCapability {

    /** OAuth2.0 换票端点路径（不需签名；与 param2 业务端点不同前缀）。 */
    static final String AUTH_PATH = "/auth/system.oauth2/getToken";

    static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
    static final String PARAM_GRANT_TYPE = "grant_type";
    static final String PARAM_CLIENT_ID = "client_id";
    static final String PARAM_CLIENT_SECRET = "client_secret";
    static final String PARAM_REFRESH_TOKEN = "refresh_token";
    static final String RESP_ACCESS_TOKEN = "access_token";
    static final String RESP_REFRESH_TOKEN = "refresh_token";
    static final String RESP_EXPIRES_IN = "expires_in";
    static final String RESP_ERROR = "error";
    static final String RESP_ERROR_DESCRIPTION = "error_description";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Ali1688AdapterConfig config;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Clock clock;

    public Ali1688Auth(Ali1688AdapterConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
                new ObjectMapper(), Clock.systemUTC());
    }

    Ali1688Auth(Ali1688AdapterConfig config, HttpClient http, ObjectMapper mapper, Clock clock) {
        this.config = config;
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public CredentialView refresh(CredentialView stale) throws AdapterException {
        Ali1688Credential credential = Ali1688Credential.from(stale);
        if (credential.refreshToken() == null || credential.refreshToken().isBlank()) {
            throw AdapterException.nonRetryable("missing-refresh-token",
                    "1688 凭据缺少 refresh_token：长期 token 形态（v1 主形态）无法自动刷新，"
                            + "须人工在渠道后台重填（见 adapter-1688/README.md「认证接入」）");
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put(PARAM_GRANT_TYPE, GRANT_TYPE_REFRESH_TOKEN);
        params.put(PARAM_CLIENT_ID, credential.appKey());
        params.put(PARAM_CLIENT_SECRET, credential.appSecret());
        params.put(PARAM_REFRESH_TOKEN, credential.refreshToken());

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(config.gatewayBaseUrl() + AUTH_PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", Ali1688Http.FORM_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(Ali1688Http.formBody(params)))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw AdapterException.retryable("network", "请求 1688 换票端点失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AdapterException.retryable("interrupted", "请求 1688 换票端点被中断");
        }

        int status = response.statusCode();
        if (status == 429 || status >= 500) {
            throw AdapterException.retryable(Integer.toString(status),
                    "1688 换票端点临时故障/限流（HTTP " + status + "）",
                    Ali1688Http.retryAfter(response));
        }
        if (status < 200 || status >= 300) {
            JsonNode body = parse(response.body());
            throw AdapterException.nonRetryable(
                    text(body, RESP_ERROR, Integer.toString(status)),
                    "1688 换票被拒绝: " + text(body, RESP_ERROR_DESCRIPTION, "HTTP " + status));
        }
        return toCredentialView(credential, parse(response.body()));
    }

    /**
     * 官方出参：{@code access_token / refresh_token / expires_in / aliId / resource_owner / memberId}
     * （{@code expires_in} 示例为秒，字符串或数值形态均接受）。
     */
    private CredentialView toCredentialView(Ali1688Credential credential, JsonNode body) {
        String accessToken = text(body, RESP_ACCESS_TOKEN, null);
        if (accessToken == null) {
            // 换票失败也可能是 error/error_description 随 200 返回——同样按业务拒绝处理
            throw AdapterException.nonRetryable(text(body, RESP_ERROR, "bad-token-response"),
                    "1688 换票响应缺少 access_token: "
                            + text(body, RESP_ERROR_DESCRIPTION, body.toString()));
        }
        JsonNode expiresIn = body.path(RESP_EXPIRES_IN);
        String expiresAt = null;
        if (expiresIn.isNumber()) {
            expiresAt = Instant.now(clock).plusSeconds(expiresIn.asLong()).toString();
        } else if (expiresIn.isTextual() && !expiresIn.asText().isBlank()) {
            expiresAt = Instant.now(clock).plusSeconds(Long.parseLong(expiresIn.asText().trim()))
                    .toString();
        }
        return credential.toCredentialView(expiresAt, accessToken, text(body, RESP_REFRESH_TOKEN, null));
    }

    private JsonNode parse(String responseBody) {
        try {
            return mapper.readTree(responseBody);
        } catch (IOException e) {
            throw AdapterException.nonRetryable("bad-response", "换票响应非合法 JSON: " + e.getMessage());
        }
    }

    private static String text(JsonNode body, String field, String fallback) {
        String value = Ali1688Json.text(body, field);
        return value == null || value.isBlank() ? fallback : value;
    }
}
