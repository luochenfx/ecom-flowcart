package io.autocommerce.adapter.ali1688;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.catalog.model.SourceRef;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.dto.OfferData;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
/**
 * 1688 OfferFetchCapability 实现（#19 子集，ADR-0007 / specs/0005）。
 *
 * <p>v1 子集范围：POST {@code source_ref.url}（application/x-www-form-urlencoded，
 * form = productId），对响应体做 1688 → {@link OfferData} 结构转换（{@link Ali1688OfferJsonMapper}）。
 * <b>真网关接入（{@code gw.open.1688.com} 签名 / access_token）不在本子集</b>——请求签名与
 * OAuth 协议归属 #23（AuthCapability + 认证 README）按官方文档实测补全；本子集以
 * 1688-shaped fixture（WireMock）验证结构转换与错误映射，无真实账号可跑。
 *
 * <p>错误语义（specs/0005 §6）：HTTP 429 / 5xx = RETRYABLE（platformCode = HTTP 状态码）；
 * 其余 4xx = NON_RETRYABLE；业务拒绝（success=false）= NON_RETRYABLE（errorCode/errorMsg）；
 * 传输层 IO 异常 = RETRYABLE —— offer 拉取是只读操作，超时/断连重试安全（不属 AMBIGUOUS，
 * AMBIGUOUS 语义为"写是否生效未知"，此处不适用）。非 AdapterException 的异常 = bug。
 */
public final class Ali1688OfferFetch implements OfferFetchCapability {

    private final HttpClient http;
    private final Ali1688OfferJsonMapper mapper;

    public Ali1688OfferFetch() {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.mapper = new Ali1688OfferJsonMapper(new ObjectMapper());
    }

    @Override
    public OfferData fetchOffer(SourceRef ref) throws AdapterException {
        if (ref == null) {
            throw AdapterException.nonRetryable("missing-source-ref", "source_ref 为空");
        }
        String url = ref.url();
        if (url == null || url.isBlank()) {
            throw AdapterException.nonRetryable("missing-url",
                    "OfferFetch v1 子集要求 source_ref.url 指向 offer JSON 端点"
                            + "（真网关接入 / 签名 = #23 实测补全）");
        }
        String externalId = ref.externalId();
        if (externalId == null || externalId.isBlank()) {
            throw AdapterException.nonRetryable("missing-external-id", "source_ref.external_id 为空");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", Ali1688Http.FORM_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers
                        .ofString(Ali1688Http.formBody(Map.of("productId", externalId))))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw AdapterException.retryable("network", "请求 1688 offer 失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AdapterException.retryable("interrupted", "请求 1688 offer 被中断");
        }

        int status = response.statusCode();
        if (status == 429 || status >= 500) {
            throw AdapterException.retryable(Integer.toString(status),
                    "1688 网关临时故障/限流（HTTP " + status + "）",
                    Ali1688Http.retryAfter(response));
        }
        if (status < 200 || status >= 300) {
            throw AdapterException.nonRetryable(Integer.toString(status),
                    "1688 网关拒绝（HTTP " + status + "）");
        }
        return mapper.map(response.body());
    }

}
