package io.autocommerce.api;

import io.autocommerce.core.contract.AdapterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST 错误映射（specs/0007 §6.3）。
 *
 * <table>
 *   <caption>映射表</caption>
 *   <tr><th>情形</th><th>HTTP</th><th>是否启动编排链</th></tr>
 *   <tr><td>采集抛 {@code AdapterException(RETRYABLE)}</td><td>{@code 503}</td><td>否（未落库）</td></tr>
 *   <tr><td>采集抛 {@code AdapterException(NON_RETRYABLE)}</td><td>{@code 422}</td><td>否</td></tr>
 *   <tr><td>请求体校验失败</td><td>{@code 400}</td><td>否</td></tr>
 * </table>
 *
 * <p>采集失败的「不启动编排链」由 {@link FlowController} 的控制流保证（{@code ingest} 抛异常 →
 * {@code launcher.start} 不被执行），本 advice 只负责状态码呈现；两者共同确保不产生 zombie 链。
 *
 * <p>{@code AMBIGUOUS}（spec §6.3 未列，采集路径本不应产生）：按「结果未知、未落库」收口为 {@code 503}。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AdapterException.class)
    public ResponseEntity<ApiErrorResponse> onAdapter(AdapterException exception) {
        HttpStatus status = switch (exception.kind()) {
            case RETRYABLE, AMBIGUOUS -> HttpStatus.SERVICE_UNAVAILABLE;
            case NON_RETRYABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        log.warn("采集失败映射为 {}：kind={} platformCode={} message={}",
                status.value(), exception.kind(), exception.platformCode(), exception.getMessage());
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse("adapter_error", exception.getMessage(), exception.platformCode()));
    }

    @ExceptionHandler(FlowNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> onFlowNotFound(FlowNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("flow_not_found", exception.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiErrorResponse> onInvalidRequest(InvalidRequestException exception) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("invalid_request", exception.getMessage()));
    }
}
