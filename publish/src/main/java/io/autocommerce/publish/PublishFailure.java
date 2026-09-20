package io.autocommerce.publish;

import io.autocommerce.core.contract.AdapterException;

/**
 * 一次 add / reconcile 失败的归类结果（{@link PublishFailureKind} + 结构化原因）。
 *
 * <p>{@link #classify} 是"平台异常 → 编排归类"的单一入口（AC-4）：
 * {@code AdapterException.kind()} 直接映射；<b>非</b> {@code AdapterException} 一律归
 * {@link PublishFailureKind#UNEXPECTED}（= bug，specs/0005 §6 / Testing §6：不得当临时故障重试到死）。
 */
public record PublishFailure(PublishFailureKind kind, String platformCode, String message) {

    /**
     * 平台异常 → publish 编排归类。
     *
     * @throws NullPointerException throwable 为 null
     */
    public static PublishFailure classify(Throwable throwable) {
        if (throwable == null) {
            throw new NullPointerException("throwable 必填");
        }
        if (throwable instanceof AdapterException adapter) {
            PublishFailureKind kind = switch (adapter.kind()) {
                case RETRYABLE -> PublishFailureKind.RETRYABLE;
                case NON_RETRYABLE -> PublishFailureKind.REJECTED;
                case AMBIGUOUS -> PublishFailureKind.AMBIGUOUS;
            };
            return new PublishFailure(kind, adapter.platformCode(), adapter.getMessage());
        }
        return new PublishFailure(PublishFailureKind.UNEXPECTED, null, describe(throwable));
    }

    /**
     * 结构化原因：{@code platform_code: message}（无平台码则仅 message），落投影 {@code reason} /
     * 事件 {@code listing.ambiguous.reason}。
     */
    public String reason() {
        if (platformCode == null || platformCode.isBlank()) {
            return message;
        }
        return platformCode + ": " + message;
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        String type = throwable.getClass().getName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }
}
