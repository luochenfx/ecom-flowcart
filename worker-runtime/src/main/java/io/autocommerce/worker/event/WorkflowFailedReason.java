package io.autocommerce.worker.event;

/**
 * workflow 失败错误分类（#20 AC-6 / specs/0006 §5）。与 {@code sys.workflow.failed}
 * 事件 payload {@code error_type} 枚举对齐。
 *
 * <p>三种分类对应三种上游语义：
 * <ul>
 *   <li>{@link #RETRYABLE_EXHAUSTED} —— Temporal RetryOptions maximumAttempts 耗尽
 *       （默认 v1 = 1 次，本项目里 activity 直接抛 ContentChainFailedException）；</li>
 *   <li>{@link #NON_RETRYABLE} —— Step 抛 {@code StepExecutionException} 且
 *       plan 标 {@code critical=true}（硬依赖失败，规格 §5 "不阻断铺货≠没内容也硬铺"）；</li>
 *   <li>{@link #AMBIGUOUS} —— 留缝（v1 内容链不引入 AMBIGUOUS 语义；#21 publish 铺货链会用到）。</li>
 * </ul>
 */
public enum WorkflowFailedReason {
    RETRYABLE_EXHAUSTED,
    NON_RETRYABLE,
    AMBIGUOUS
}