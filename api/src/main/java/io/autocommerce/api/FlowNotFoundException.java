package io.autocommerce.api;

/**
 * {@code GET /api/v1/flows/{workflowId}} 目标 execution 不存在 → HTTP 404。
 *
 * <p>specs/0007 §6 未规定未知 workflowId 的语义，本实现显式选择 404（而非静默 200 或 500）——与
 * Temporal 侧 {@code WorkflowNotFoundException}（gRPC NOT_FOUND）一一对应，调用方能据此区分
 * 「查不到」与「链路失败」。
 */
public class FlowNotFoundException extends RuntimeException {

    private final String workflowId;

    public FlowNotFoundException(String workflowId) {
        super("编排链不存在：" + workflowId);
        this.workflowId = workflowId;
    }

    public String workflowId() {
        return workflowId;
    }
}
