package io.autocommerce.worker.flow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;

import java.time.Duration;

/**
 * 编排链 activity 的调用选项（Composition Root 的装配参数，非业务语义）。
 *
 * <p>与内容链 {@code ContentActivityOptions} 同口径：{@code setMaximumAttempts(1)}——内容就绪断言是
 * 确定性判定（回读已落库文档），重试不会改变结论；装配 Listing 的瞬时 store 抖动由运维重放整条链兜底，
 * 不在同一 workflow 内无限退避。StartToCloseTimeout 取 2 分钟（一次 store 读改写量级）。
 */
public final class ListingFlowActivityOptions {

    private static final Duration START_TO_CLOSE_TIMEOUT = Duration.ofMinutes(2);

    private ListingFlowActivityOptions() {
    }

    /** v1 默认 activity 选项（固定值 + 单次尝试）。 */
    public static ActivityOptions defaults() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(START_TO_CLOSE_TIMEOUT)
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(1)
                        .build())
                .build();
    }
}
