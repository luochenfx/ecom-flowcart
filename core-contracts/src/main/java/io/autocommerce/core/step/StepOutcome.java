package io.autocommerce.core.step;

/**
 * Step 执行结果态（specs/0006 §5/§6）。单 Step 失败走降级而非阻断：OK = 产物正常；
 * DEGRADED = 降级产物（缺省 + reason）；SKIPPED = 该 Step 未运行。
 */
public enum StepOutcome {
    OK,
    DEGRADED,
    SKIPPED
}
