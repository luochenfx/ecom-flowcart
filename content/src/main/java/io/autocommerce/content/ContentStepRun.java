package io.autocommerce.content;

import io.autocommerce.content.model.ModelCall;
import io.autocommerce.core.step.StepOutcome;

import java.util.List;

/**
 * 单个 Step 的执行记录（specs/0006 §7"执行记录"落点）：结果态 + 降级原因 + 模型调用用量。
 * 不建计费系统——本记录供看板聚合 token/成本与 HITL 排查。
 */
public record ContentStepRun(String stepId, StepOutcome outcome, String reason, List<ModelCall> modelCalls) {

    public ContentStepRun {
        modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
    }

    /** 本次 Step 用到的模型（首个非空；无模型调用如 RULE Step = null）。 */
    public String model() {
        return modelCalls.stream().map(ModelCall::model).filter(m -> m != null && !m.isBlank())
                .findFirst().orElse(null);
    }

    /** 本次 Step 的 token 总量（provider 未回报 usage 时为 null）。 */
    public Integer totalTokens() {
        Integer sum = null;
        for (ModelCall call : modelCalls) {
            if (call.usage() != null && call.usage().totalTokens() != null) {
                sum = (sum == null ? 0 : sum) + call.usage().totalTokens();
            }
        }
        return sum;
    }

    public boolean degraded() {
        return outcome == StepOutcome.DEGRADED;
    }
}
