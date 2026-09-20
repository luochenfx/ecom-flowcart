package io.autocommerce.worker.order;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;

import java.time.Duration;

/**
 * 采购单链 activity 调用选项（Composition Root 装配参数，非业务语义）。
 *
 * <p>与 {@link OrderActivityOptions} 同口径（v1 采购链独立一张采购单，时序与订单链一致）：
 * RetryOptions.setMaximumAttempts(1) —— 采购下单 / 支付是<b>有外部副作用</b>的动作，v1 选择
 * "快速失败 + 人工/定时重放整条链"而非在同一 workflow 内静默重试（重复下单风险）；真要吃掉瞬时抖动
 * 是在这里调大 attempts（唯一改动点），activity 与 workflow 都不受影响。
 */
public final class PurchaseActivityOptions {

    private static final Duration DEFAULT_START_TO_CLOSE_TIMEOUT = Duration.ofMinutes(2);

    private PurchaseActivityOptions() {
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
