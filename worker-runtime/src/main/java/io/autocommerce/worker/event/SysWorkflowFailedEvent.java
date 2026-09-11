package io.autocommerce.worker.event;

import io.autocommerce.content.ContentChainFailedException;

/**
 * 硬依赖 workflow 失败事件载荷（#20 AC-6 / specs/0006 §5）。payload 不依赖业务库行——
 * 仅引用 Temporal workflow 坐标 + 失败 Step + 原因。**关键约束**：本事件**不替代** Temporal
 * workflow 终态（仍走 Temporal ActivityFailure → workflow failed），仅给看板 / 告警订阅做
 * 业务级信号源。
 *
 * <p><b>A-prime 降级语义</b>（specs/0006 §5 #20 拍板）：process 在落库后、发事件前 crash → 事件丢失；
 * 看板错过告警。**对冲**：订阅方需配合 Temporal UI 巡检兜底（Temporal event history 是真相源，
 * {@code sys.workflow.failed} 仅是 signal）。事务基建（落库与发事件同事务）属 #22 publish 跨域基建，
 * #20 不引入。
 *
 * @param workflowType workflow 类型（如 {@code ContentWorkflow}）
 * @param workflowId   Temporal workflowId（业务键语义，对齐 #11 确定性 workflowId）
 * @param runId        Temporal RunId（同 workflowId 重跑会变）
 * @param spuId        业务键：内容链的目标 SPU
 * @param listingId    业务键：内容链的目标 Listing
 * @param failedStep   失败的 Step id（{@link ContentChainFailedException#stepId()}）
 * @param errorType    失败分类（{@link WorkflowFailedReason}）
 * @param reason       失败原因（{@link ContentChainFailedException#getMessage()}）
 */
public record SysWorkflowFailedEvent(String workflowType, String workflowId, String runId, String spuId,
                                     String listingId, String failedStep, WorkflowFailedReason errorType,
                                     String reason) {

    public SysWorkflowFailedEvent {
        if (workflowType == null || workflowType.isBlank()) {
            throw new IllegalArgumentException("workflowType 必填");
        }
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId 必填");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 必填");
        }
        if (errorType == null) {
            throw new IllegalArgumentException("errorType 必填");
        }
    }
}