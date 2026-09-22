package io.autocommerce.api;

import io.autocommerce.worker.flow.ListingFlowWorkflowResult;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 链路查询（specs/0007 §6.1 的 {@code GET /api/v1/flows/{workflowId}}）：把 Temporal execution 状态
 * 与（若已收敛的）编排链结果翻译成 {@link FlowQueryResponse}。
 *
 * <p>这是「跑通全流程」可自动验证的关键——没有它，链路是否收敛只能靠去 Temporal UI 肉眼看。
 *
 * <p>状态映射（Temporal → API）：
 * <ul>
 *   <li>{@code RUNNING} → {@code RUNNING}（含长期挂起等裁定，specs/0007 §4.5）；</li>
 *   <li>{@code COMPLETED} → {@code COMPLETED} + 结果字段；</li>
 *   <li>{@code FAILED} → {@code FAILED} + 失败摘要；</li>
 *   <li>其它终态 → 透传状态名（不取结果，避免对 {@code CONTINUED_AS_NEW} 之类阻塞）。</li>
 * </ul>
 *
 * <p>未知 workflowId → {@link FlowNotFoundException}（HTTP 404），不静默 200 / 500。
 */
@Service
public class FlowQueryService {

    private static final String STATUS_PREFIX = "WORKFLOW_EXECUTION_STATUS_";

    private final WorkflowClient client;

    public FlowQueryService(WorkflowClient client) {
        this.client = Objects.requireNonNull(client, "WorkflowClient 必填");
    }

    /** 按 workflowId 回读编排链状态与结果。 */
    public FlowQueryResponse query(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new InvalidRequestException("workflowId 必填");
        }
        WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
        WorkflowExecutionStatus status = describeStatus(stub, workflowId);

        if (status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) {
            return FlowQueryResponse.running(workflowId);
        }
        if (status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED) {
            return FlowQueryResponse.completed(workflowId,
                    stub.getResult(ListingFlowWorkflowResult.class));
        }
        if (status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED) {
            return FlowQueryResponse.failed(workflowId, failureReason(stub));
        }
        return FlowQueryResponse.terminal(workflowId, normalize(status));
    }

    private static WorkflowExecutionStatus describeStatus(WorkflowStub stub, String workflowId) {
        try {
            WorkflowExecutionDescription description = stub.describe();
            return description.getStatus();
        } catch (WorkflowNotFoundException e) {
            throw new FlowNotFoundException(workflowId);
        }
    }

    /**
     * 失败摘要的稳定分类前缀。归一化文案 = 固定前缀 + 顶层异常类型名，**不原样透传底层异常
     * {@code e.getMessage()}**（其长度不可控、且可能泄漏内部实现细节）。
     */
    private static final String FAILURE_REASON_PREFIX = "workflow_failed:";

    /**
     * 失败摘要：归一化为**稳定、有限**的文案——固定分类前缀 {@code workflow_failed:} 拼接顶层异常
     * 的简单类名（如 {@code workflow_failed:WorkflowFailedException}）。两部分都有限：前缀为常量，类名是
     * 编译期确定的 Java 标识符。
     *
     * <p>刻意不返回底层 {@code e.getMessage()}：原先的直接透传既有「长度不可控」问题，也可能泄漏内部
     * 细节。取 {@link WorkflowException} 的失败信息（失败路径 {@code getResult} 立即抛，不阻塞）；成功
     * 返回时（不该发生的 {@code getResult} 不抛）回落为 {@code null}。
     *
     * <p>{@code FAILED} 恒有非空 {@code reason}、{@code RUNNING} 的 {@code reason} 恒为 {@code null}，
     * 两者据此仍可区分（AC-8 语义不受影响）。
     */
    private static String failureReason(WorkflowStub stub) {
        try {
            stub.getResult(ListingFlowWorkflowResult.class);
            return null;
        } catch (WorkflowException e) {
            return FAILURE_REASON_PREFIX + e.getClass().getSimpleName();
        }
    }

    private static String normalize(WorkflowExecutionStatus status) {
        String name = status.name();
        return name.startsWith(STATUS_PREFIX) ? name.substring(STATUS_PREFIX.length()) : name;
    }
}
