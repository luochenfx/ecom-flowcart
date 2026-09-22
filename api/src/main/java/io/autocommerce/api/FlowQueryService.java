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

    /** 失败摘要：取执行结果的失败信息（失败路径 {@code getResult} 立即抛，不阻塞）。 */
    private static String failureReason(WorkflowStub stub) {
        try {
            stub.getResult(ListingFlowWorkflowResult.class);
            return null;
        } catch (WorkflowException e) {
            String message = e.getMessage();
            return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        }
    }

    private static String normalize(WorkflowExecutionStatus status) {
        String name = status.name();
        return name.startsWith(STATUS_PREFIX) ? name.substring(STATUS_PREFIX.length()) : name;
    }
}
