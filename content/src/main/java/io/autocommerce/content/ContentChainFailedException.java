package io.autocommerce.content;

/**
 * 硬依赖 Step 失败 → 内容链 failed（specs/0006 §5："硬依赖 Step 失败：内容链 failed（Listing 不进
 * 铺货队列）"）。编排层（Temporal workflow 的 activity）以此异常让执行失败，走 #13 的重放/告警路径；
 * 非硬依赖 Step 失败不抛此异常（降级 + {@code degraded_steps}，内容链继续）。
 */
public class ContentChainFailedException extends RuntimeException {

    private final String stepId;

    public ContentChainFailedException(String stepId, String message, Throwable cause) {
        super("内容链硬依赖 Step 失败 [" + stepId + "]: " + message, cause);
        this.stepId = stepId;
    }

    /** 失败的 Step id。 */
    public String stepId() {
        return stepId;
    }
}
