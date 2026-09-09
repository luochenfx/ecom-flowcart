package io.autocommerce.core.step;

/**
 * Step 执行失败（非业务降级路径的异常）。Step 必须无状态、输出只由输入 + 参数决定
 * （workflow 重放安全）；LLM 非确定性由"产物落库为终稿、重跑才覆盖"吸收。
 * 内容链 workflow 以该异常区分"硬失败（failed）"与"可降级（DEGRADED）"。
 */
public class StepExecutionException extends RuntimeException {

    public StepExecutionException(String message) {
        super(message);
    }

    public StepExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
