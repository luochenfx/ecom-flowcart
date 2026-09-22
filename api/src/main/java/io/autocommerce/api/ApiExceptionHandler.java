package io.autocommerce.api;

import io.autocommerce.core.contract.AdapterException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
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
 *   <tr><td>重复提交 / 链路已存在（{@code WorkflowExecutionAlreadyStarted}）</td><td>{@code 409}</td><td>否（既有链不动）</td></tr>
 * </table>
 *
 * <p>采集失败的「不启动编排链」由 {@link FlowController} 的控制流保证（{@code ingest} 抛异常 →
 * {@code launcher.start} 不被执行），本 advice 只负责状态码呈现；两者共同确保不产生 zombie 链。
 *
 * <p>{@code AMBIGUOUS}（spec §6.3 未列，采集路径本不应产生）：按「结果未知、未落库」收口为 {@code 503}。
 *
 * <p><b>重复提交为何是 {@code 409} 而非幂等 {@code 202}（design decision）</b>：冲突策略
 * （{@code WORKFLOW_ID_CONFLICT_POLICY_FAIL} / {@code ALLOW_DUPLICATE_FAILED_ONLY}）命中既有链时，
 * <b>本次并未启动任何新链</b>。若回 {@code 202} 会谎报「刚触发了一条新链」，调用方据此做出的决策会错
 * ——故用 {@code 409 Conflict} 如实表达「与既有链路冲突、本次未产生新链」，并在错误体带上既有链坐标
 * （{@code flow_workflow_id}）供回读。这是 spec §6.3 失败映射表尚未列出的情形（见 issue #73 登记）。
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
                .body(ApiErrorResponse.adapter("adapter_error", exception.getMessage(), exception.platformCode()));
    }

    /**
     * 重复提交 / 链路已存在 → {@code 409 Conflict}（不产生新链）。
     *
     * <p>只兜「链路已存在 / 已启动」这一**精确**情形：{@link WorkflowExecutionAlreadyStarted}
     * 是 Temporal 在冲突策略拒绝重复 start 时抛出的**终止类型**（{@code final}，直接父类为
     * {@link io.temporal.client.WorkflowException}）。刻意**不** catch 更宽的 {@code WorkflowException}
     * / {@code RuntimeException}——那些还覆盖 {@code WorkflowServiceException} 等真实服务故障，若一并
     * 收口为 409 会把真缺陷吞掉。
     */
    @ExceptionHandler(WorkflowExecutionAlreadyStarted.class)
    public ResponseEntity<ApiErrorResponse> onFlowAlreadyStarted(WorkflowExecutionAlreadyStarted exception) {
        String flowWorkflowId = exception.getExecution() == null
                ? null
                : exception.getExecution().getWorkflowId();
        log.warn("重复提交：编排链已存在，映射为 409（未启动新链）flowWorkflowId={}", flowWorkflowId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.alreadyExists(
                        "flow_already_exists",
                        "同 (spu_id, channel_id) 的编排链已存在，本次未启动新链；"
                                + "请按 flow_workflow_id 查询既有链路状态",
                        flowWorkflowId));
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
