package io.autocommerce.worker.content;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;

import java.time.Duration;

/**
 * 内容链 activity 的调用选项（Composition Root 的装配参数，非业务语义）。
 *
 * <p><b>RetryOptions.setMaximumAttempts(1) 是有意为之</b>（#20 AC-6）：硬依赖失败要让内容链
 * **failed** 并走重放 / 告警，而不是在同一 workflow 内静默重试到天荒地老——LLM 端点长时间不可用时，
 * "快速失败 + 人工/定时重放整条链"比"占用 workflow 槽位无限退避"更可运维。真要把瞬时抖动也吃掉，
 * 是在这里调大 attempts（唯一改动点），Step 与 workflow 都不受影响。
 *
 * <p>StartToCloseTimeout 取 2 分钟：单步 = 一段文案的 LLM 调用或若干张图下载，超过即视为挂死；
 * 总时长上限由 workflow 侧的整体超时兜底（装配时给 WorkflowOptions）。
 */
public final class ContentActivityOptions {

    private static final Duration DEFAULT_START_TO_CLOSE_TIMEOUT = Duration.ofMinutes(2);

    private ContentActivityOptions() {
    }

    /** v1 默认 activity 选项（固定值 + 单次尝试，见类 javadoc）。 */
    public static ActivityOptions defaults() {
        return builder()
                .setStartToCloseTimeout(DEFAULT_START_TO_CLOSE_TIMEOUT)
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(1)
                        .build())
                .build();
    }

    /** 可调装配入口（生产把 StartToClose / attempts 暴露给配置时走这里）。 */
    public static ActivityOptions.Builder builder() {
        return ActivityOptions.newBuilder();
    }
}
