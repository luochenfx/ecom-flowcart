package io.autocommerce.adapter.ali1688;

import io.autocommerce.core.contract.AdapterErrorKind;
import io.autocommerce.core.contract.AdapterException;

import java.time.Duration;
import java.util.Locale;

/**
 * 1688 Adapter 错误映射集中处（specs/0005 §6、ADR-0007）——HTTP 状态码 / 官方错误码 /
 * 必填值校验 → 三类 {@link AdapterException} 的<b>判定逻辑只此一处</b>，其余类只调用不判。
 *
 * <p>零 Spring 依赖（ArchUnit 护栏 {@code ADAPTER_ONLY_DEPENDS_ON_CORE}）：只用 core 契约与 JDK。
 * 不引 Map / DTO / 反射 / DSL（ADR-0007 明确否决"造映射 DSL"）。
 *
 * <p>口径归属：
 * <ul>
 *   <li>{@link #throwIfHttpFailure} / {@link #throwIfHttpTransientFailure}：HTTP 状态码 → 三类；</li>
 *   <li>{@link #businessRejection}：业务拒绝（{@code success=false}）→ 按官方错误码定性；</li>
 *   <li>{@link #classify}：官方错误码 → 平台侧临时故障（RETRYABLE）/ 业务拒绝（NON_RETRYABLE）；</li>
 *   <li>{@link #requireNonBlank}：必填值缺失 → NON_RETRYABLE（errorCode / 文案由调用方参数化）。</li>
 * </ul>
 */
final class Ali1688ErrorMapping {

    private Ali1688ErrorMapping() {
    }

    /**
     * HTTP 状态码 → 三类：429 / 5xx → RETRYABLE（带 {@code Retry-After} 窗口）；其余非 2xx →
     * NON_RETRYABLE；2xx → 无错返回。<b>由 HTTP 状态码驱动的判定只此一处。</b>
     *
     * @param status        HTTP 状态码
     * @param endpointLabel 端点标签（如 {@code "1688 网关"} / {@code "1688 换票端点"}），仅用于消息
     * @param retryAfter    平台 {@code Retry-After} 退避窗口（缺失 / 非法 → {@code null}）
     */
    static void throwIfHttpFailure(int status, String endpointLabel, Duration retryAfter) {
        throwIfHttpTransientFailure(status, endpointLabel, retryAfter);
        if (status < 200 || status >= 300) {
            throw AdapterException.nonRetryable(Integer.toString(status),
                    endpointLabel + "拒绝（HTTP " + status + "）");
        }
    }

    /**
     * 仅 429 / 5xx → RETRYABLE（带窗口）；其余（含 4xx）→ 无错返回。
     *
     * <p>供 4xx 需从响应体取 {@code error} / {@code error_description} 的调用点（OAuth 换票）单独使用——
     * 这类 4xx 的 errorCode / 消息来自响应体而非状态码，故不走 {@link #throwIfHttpFailure} 的 4xx 分支。
     */
    static void throwIfHttpTransientFailure(int status, String endpointLabel, Duration retryAfter) {
        if (status == 429 || status >= 500) {
            throw AdapterException.retryable(Integer.toString(status),
                    endpointLabel + "临时故障/限流（HTTP " + status + "）", retryAfter);
        }
    }

    /**
     * 业务拒绝（{@code success=false}）→ 按官方错误码 {@link #classify} 定 RETRYABLE / NON_RETRYABLE。
     *
     * @param code    官方错误码（{@code null} → {@code "unknown"}，落 {@code platformCode}）
     * @param message 官方错误消息（各端点字段命名不统一，取值由调用方按端点形态完成）
     */
    static AdapterException businessRejection(String code, String message) {
        String platformCode = code == null ? "unknown" : code;
        return classify(code) == AdapterErrorKind.RETRYABLE
                ? AdapterException.retryable(platformCode, "1688 平台侧临时故障: " + message)
                : AdapterException.nonRetryable(platformCode, "1688 业务拒绝: " + message);
    }

    /**
     * 官方错误码 → 三类（ADR-0007：平台错误码知识在 Adapter 内）。
     * fastCreateOrder 官方错误码表：{@code 400*} 参数/业务拒绝、{@code FAIL_BIZ_*} 业务规则、
     * {@code 500 view order service error} 平台侧；代销未授权 / 库存不足等均为业务拒绝。
     *
     * <p><b>判 RETRYABLE 的口径刻意收窄到"平台侧"特征串</b>：业务错误码里也常带
     * {@code LIMIT} / {@code QUANTITY}（如起批量、最大购买量限制），宽泛匹配
     * （只要含 LIMIT 就重试）会把"业务拒绝"误判成"临时故障"而重试到死。宁可漏判成
     * NON_RETRYABLE（落 Saga 人工处理），也不要把业务拒绝当作抖动。
     */
    static AdapterErrorKind classify(String code) {
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

    /**
     * 必填值缺失（{@code null} / 空白）→ NON_RETRYABLE（配置或代码问题，重试无意义）。
     *
     * <p>errorCode 与消息文案由调用方<b>参数化</b>：凭据键缺失（{@code missing-credential}）、
     * 交易请求体必填字段（{@code missing-required-field}）等语义各不相同，不得统一成一种文案。
     */
    static String requireNonBlank(String value, String errorCode, String message) {
        if (value == null || value.isBlank()) {
            throw AdapterException.nonRetryable(errorCode, message);
        }
        return value;
    }
}
