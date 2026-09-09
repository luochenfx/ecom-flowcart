package io.autocommerce.core.contract;

/**
 * 平台错误统一契约（ADR-0007 / specs/0005 §6）。
 * 所有能力接口只抛本异常、不裸抛平台 SDK 异常；非 AdapterException 的异常 = bug 而非平台错误，
 * core 侧拦截按 NON_RETRYABLE 收口（防把代码缺陷当临时故障重试到死）。
 *
 * @param kind            错误三类，见 {@link AdapterErrorKind}
 * @param platformCode    平台原始错误码（落 reason / last_error），可空
 * @param message         可读描述
 * @param retryableAfter  平台 Retry-After/节流窗口（供退避），可空
 */
public class AdapterException extends RuntimeException {

    private final AdapterErrorKind kind;
    private final String platformCode;
    private final java.time.Duration retryableAfter;

    public AdapterException(AdapterErrorKind kind, String platformCode, String message,
                            java.time.Duration retryableAfter) {
        super(message);
        this.kind = kind;
        this.platformCode = platformCode;
        this.retryableAfter = retryableAfter;
    }

    public static AdapterException retryable(String platformCode, String message) {
        return new AdapterException(AdapterErrorKind.RETRYABLE, platformCode, message, null);
    }

    public static AdapterException nonRetryable(String platformCode, String message) {
        return new AdapterException(AdapterErrorKind.NON_RETRYABLE, platformCode, message, null);
    }

    public static AdapterException ambiguous(String platformCode, String message) {
        return new AdapterException(AdapterErrorKind.AMBIGUOUS, platformCode, message, null);
    }

    public AdapterErrorKind kind() {
        return kind;
    }

    public String platformCode() {
        return platformCode;
    }

    public java.time.Duration retryableAfter() {
        return retryableAfter;
    }
}
