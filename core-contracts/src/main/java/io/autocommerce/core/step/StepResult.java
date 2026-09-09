package io.autocommerce.core.step;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Step 执行结果（specs/0006 §3 StepResult）。DEGRADED 时 reason 为降级原因（写 Listing
 * degraded_steps 供看板 HITL）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StepResult(StepOutcome outcome, String reason) {

    public static StepResult ok() {
        return new StepResult(StepOutcome.OK, null);
    }

    public static StepResult degraded(String reason) {
        return new StepResult(StepOutcome.DEGRADED, reason);
    }
}
