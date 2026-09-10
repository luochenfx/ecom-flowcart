package io.autocommerce.adapter.ali1688;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 1688 网关 HTTP 细节（POST form 编码 / {@code Retry-After} 解析）——Adapter 内共享，
 * {@link Ali1688OfferFetch}（url 直连）与 {@link Ali1688Gateway}（param2 签名网关）同用一套。
 */
final class Ali1688Http {

    static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    private Ali1688Http() {
    }

    /**
     * 参数 → {@code application/x-www-form-urlencoded} 请求体（RFC 1866：空格编码为 {@code +}）。
     * 1688 的 JSON 型参数（cargoParamList / addressParam）以<b>值</b>身份参与编码。
     */
    static String formBody(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((key, value) -> {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    /**
     * 解析标准 HTTP {@code Retry-After} 头（delta-seconds）→ 退避窗口（specs/0005 §6）。
     * 缺失 / 非法 / HTTP-date 形态 → {@code null}（退避交给调用方默认策略）。
     */
    static Duration retryAfter(HttpResponse<String> response) {
        String value = response.headers().firstValue("Retry-After").orElse(null);
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            // HTTP-date 形态（RFC 9110 §10.2.3）官方网关未见；需要时按 RFC 解析，不猜
            return null;
        }
    }
}
