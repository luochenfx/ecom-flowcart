package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * sys.workflow.failed 事件负载（schema: SysWorkflowFailedPayload，Lifecycle 事件）。
 * workflow failed 终态广播（告警/看板）；与 Domain Event 同 envelope，type 前缀 sys.* 区分。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SysWorkflowFailedPayload(
        String workflowType,
        String workflowId,
        String runId,
        WorkflowErrorType errorType,
        String reason) {
}
