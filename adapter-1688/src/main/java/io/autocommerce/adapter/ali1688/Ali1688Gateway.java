package io.autocommerce.adapter.ali1688;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.contract.AdapterErrorKind;
import io.autocommerce.core.contract.AdapterException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 1688 param2 网关客户端（specs/0005 §5「签名差异 = 平台知识 → Adapter 内实现」、
 * §6「限流自治在 Adapter 内」）。
 *
 * <p>一次调用 = 令牌桶排队 → 组装系统参数（{@code _aop_timestamp} / {@code access_token} /
 * {@code webSite}）→ HMAC-SHA1 签名 → POST form → 错误三类映射。<b>只抛
 * {@link AdapterException}</b>：
 * <ul>
 *   <li>HTTP 429 / 5xx → RETRYABLE（带 {@code Retry-After} 窗口）；</li>
 *   <li>其余 HTTP 4xx、业务拒绝（{@code success=false}）→ NON_RETRYABLE
 *       （例外：官方错误码 {@code 500*} / {@code *SYSTEM_ERROR} / {@code *LIMIT*} 属平台侧临时故障 → RETRYABLE）；</li>
 *   <li><b>写操作</b>超时 / 中断 → AMBIGUOUS（请求可能在途，不重发，交 reconcile）；读操作 → RETRYABLE。</li>
 * </ul>
 *
 * <p>不依赖 Spring：{@link HttpClient} 由 JDK 提供，构造可注入（测试替换超时 / 时钟 / 令牌桶）。
 */
final class Ali1688Gateway {

    static final String PARAM_ACCESS_TOKEN = "access_token";
    static final String PARAM_TIMESTAMP = "_aop_timestamp";
    static final String PARAM_WEB_SITE = "webSite";
    static final String SITE_1688 = "1688";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Ali1688AdapterConfig config;
    private final HttpClient http;
    private final Ali1688RateLimiter limiter;
    private final ObjectMapper mapper;
    private final LongSupplier clockMillis;

    Ali1688Gateway(Ali1688AdapterConfig config) {
        this(config,
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
                new Ali1688RateLimiter(config.permitsPerSecond(), config.burst()),
                new ObjectMapper(),
                System::currentTimeMillis);
    }

    Ali1688Gateway(Ali1688AdapterConfig config, HttpClient http, Ali1688RateLimiter limiter,
                   ObjectMapper mapper, LongSupplier clockMillis) {
        this.config = config;
        this.http = http;
        this.limiter = limiter;
        this.mapper = mapper;
        this.clockMillis = clockMillis;
    }

    /**
     * 调用一个 param2 端点，返回<b>未拆包装</b>的响应体（各端点的出参形态不同：
     * {@code result} / 顶层 {@code logisticsTrace} / 裸 {@code success}，拆包由各能力自己做）。
     *
     * @param api    端点（决定 URL、签名因子一、读写语义）
     * @param params 应用级参数（值已格式化；系统参数与签名由本方法补）
     */
    JsonNode call(Ali1688Api api, Map<String, String> params) {
        Ali1688Credential credential = requireCredential();
        limiter.acquire();

        Map<String, String> signed = signedParams(api, credential, params);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(config.gatewayBaseUrl() + api.path(credential.appKey())))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", Ali1688Http.FORM_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(Ali1688Http.formBody(signed)))
                .build();

        HttpResponse<String> response = send(api, request);
        int status = response.statusCode();
        if (status == 429 || status >= 500) {
            throw AdapterException.retryable(Integer.toString(status),
                    "1688 网关临时故障/限流（HTTP " + status + "）", Ali1688Http.retryAfter(response));
        }
        if (status < 200 || status >= 300) {
            throw AdapterException.nonRetryable(Integer.toString(status),
                    "1688 网关拒绝（HTTP " + status + "）");
        }
        JsonNode body = parse(response.body());
        rejectBusinessError(body);
        return body;
    }

    /** 补系统参数并签名：参与签名的 = 除 {@code _aop_signature} 外全部实际发送参数。 */
    private Map<String, String> signedParams(Ali1688Api api, Ali1688Credential credential,
                                             Map<String, String> params) {
        Map<String, String> all = new LinkedHashMap<>(params);
        if (api.siteAware()) {
            all.put(PARAM_WEB_SITE, SITE_1688);
        }
        all.put(PARAM_TIMESTAMP, Long.toString(clockMillis.getAsLong()));
        all.put(PARAM_ACCESS_TOKEN, credential.accessToken());
        String urlPath = Ali1688Signature.urlPath(api.namespace(), api.apiName(),
                credential.appKey());
        all.put(Ali1688Signature.SIGNATURE_PARAM,
                Ali1688Signature.sign(credential.appSecret(), urlPath, all));
        return all;
    }

    private HttpResponse<String> send(Ali1688Api api, HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // 含 HttpTimeoutException：写在途未知 → AMBIGUOUS（不重发）；读 → 重试安全
            throw transportFailure(api, "请求 1688 失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw transportFailure(api, "请求 1688 被中断");
        }
    }

    private AdapterException transportFailure(Ali1688Api api, String message) {
        return api.write()
                ? AdapterException.ambiguous("network", message + "（写操作在途，不重发，走 reconcile）")
                : AdapterException.retryable("network", message);
    }

    private JsonNode parse(String responseBody) {
        try {
            return mapper.readTree(responseBody);
        } catch (IOException e) {
            throw AdapterException.nonRetryable("bad-response", "响应体非合法 JSON: " + e.getMessage());
        }
    }

    /**
     * 业务拒绝：官方文档出参里 {@code success=false} 时错误字段<b>命名不统一</b>——
     * fastCreateOrder 用 {@code code}/{@code message}，cancel / 物流用 {@code errorCode}/{@code errorMessage}，
     * product.get 用 {@code errorCode}/{@code errorMsg}。此处三种命名都读，缺则填默认值。
     */
    private void rejectBusinessError(JsonNode body) {
        JsonNode success = body.path("success");
        if (!success.isBoolean() || success.asBoolean()) {
            return;
        }
        String code = Ali1688Json.firstText(body, "code", "errorCode");
        String message = Ali1688Json.firstText(body, "message", "errorMessage", "errorMsg");
        AdapterErrorKind kind = classify(code);
        AdapterException failure = kind == AdapterErrorKind.RETRYABLE
                ? AdapterException.retryable(code == null ? "unknown" : code,
                        "1688 平台侧临时故障: " + message)
                : AdapterException.nonRetryable(code == null ? "unknown" : code,
                        "1688 业务拒绝: " + message);
        throw failure;
    }

    /**
     * 官方错误码 → 三类（ADR-0007：平台错误码知识在 Adapter 内）。
     * fastCreateOrder 官方错误码表：{@code 400*} 参数/业务拒绝、{@code FAIL_BIZ_*} 业务规则、
     * {@code 500 view order service error} 平台侧；代销未授权 / 库存不足等均为业务拒绝。
     *
     * <p><b>判 RETRYABLE 的口径刻意收窄到"平台侧"特征串</b>：业务错误码里也常带
     * {@code LIMIT} / {@code QUANTITY}（如起批量、最大购买量限制），宽泛匹配
     * （只要含 LIMIT 就重试）会把"业务拒绝"误判成"临时故障"而重试到死。宁可漏判成
     * NON_RETRYABLE（落 Saga 人工处理），也不要把业务拒绝当抖动。
     */
    private static AdapterErrorKind classify(String code) {
        if (code == null) {
            return AdapterErrorKind.NON_RETRYABLE;
        }
        String normalized = code.toUpperCase(Locale.ROOT);
        boolean platformSide = normalized.startsWith("500")
                || normalized.contains("SYSTEM_ERROR")
                || normalized.contains("SYSTEM_BUSY")
                || normalized.contains("SERVICE_UNAVAILABLE")
                || normalized.contains("TP_EXCEPTION")
                || normalized.contains("ACCESS_LIMIT")
                || normalized.contains("FLOW_LIMIT")
                || normalized.contains("QPS")
                || normalized.contains("TOO_MANY_REQUESTS");
        return platformSide ? AdapterErrorKind.RETRYABLE : AdapterErrorKind.NON_RETRYABLE;
    }

    /** 未装配凭据 = 配置问题（NON_RETRYABLE，重试无意义）。 */
    private Ali1688Credential requireCredential() {
        Ali1688Credential credential = config.credential();
        if (credential == null) {
            throw AdapterException.nonRetryable("missing-credential",
                    "1688 Adapter 未装配凭据：采购 / 认证链路需要解密后的 CredentialView"
                            + "（装配方式见 adapter-1688/README.md「认证接入」）");
        }
        return credential;
    }
}
