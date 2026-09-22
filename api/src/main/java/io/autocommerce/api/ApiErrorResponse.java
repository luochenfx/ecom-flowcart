package io.autocommerce.api;

/**
 * 错误响应体。载荷命名 = snake_case（全局策略，见 {@link ApiJacksonConfiguration}）。{@code code} 为
 * 稳定的机器可读分类，{@code detail} 为人类可读描述，{@code platform_code} 透传平台原始错误码
 * （仅 adapter 错误，可空）。
 */
public record ApiErrorResponse(String code, String detail, String platformCode) {

    public static ApiErrorResponse of(String code, String detail) {
        return new ApiErrorResponse(code, detail, null);
    }
}
