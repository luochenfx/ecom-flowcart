package io.autocommerce.api;

/**
 * 错误响应体。载荷命名 = snake_case（全局策略，见 {@link ApiJacksonConfiguration}）。{@code code} 为
 * 稳定的机器可读分类，{@code detail} 为人类可读描述，{@code platform_code} 透传平台原始错误码
 * （仅 adapter 错误，可空）。
 *
 * <p>{@code flow_workflow_id} 为**新增的可选字段**：仅「重复提交 / 链路已存在」（HTTP 409）时携带既有
 * 编排链坐标，供调用方直接 {@code GET /api/v1/flows/{flowWorkflowId}} 回读既有链路状态；其余错误为
 * {@code null}。这是纯**追加**——既有字段（{@code code} / {@code detail} / {@code platform_code}）的
 * 语义与顺序不变，不破坏既有响应形状。
 */
public record ApiErrorResponse(String code, String detail, String platformCode, String flowWorkflowId) {

    /** 无附加坐标的通用错误（400 / 404 等）。 */
    public static ApiErrorResponse of(String code, String detail) {
        return new ApiErrorResponse(code, detail, null, null);
    }

    /** adapter 错误：透传平台原始错误码（503 / 422）。 */
    public static ApiErrorResponse adapter(String code, String detail, String platformCode) {
        return new ApiErrorResponse(code, detail, platformCode, null);
    }

    /** 链路已存在（409）：携带既有编排链坐标，供调用方回读既有链路。 */
    public static ApiErrorResponse alreadyExists(String code, String detail, String flowWorkflowId) {
        return new ApiErrorResponse(code, detail, null, flowWorkflowId);
    }
}
