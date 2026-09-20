package io.autocommerce.worker.order;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;

import java.time.Duration;

/**
 * 订单链 activity 调用选项（Composition Root 装配参数，非业务语义）。
 *
 * <p>RetryOptions.setMaximumAttempts(1) 与内容链同口径：采购下单 / 发货回传是<b>有外部副作用</b>的
 * 动作，v1 选择"快速失败 + 人工/定时重放整条链"而非在同一 workflow 内静默重试（重复下单风险）；
 * 真要吃掉瞬时抖动是在这里调大 attempts（唯一改动点），activity 与 workflow 都不受影响。
 *
 * <p>StartToCloseTimeout 取 2 分钟：单步 = 一段平台 API 调用（下单 / 支付 / 物流 / 退款查询），
 * 超过即视为挂死；总时长上限由 workflow 侧整体超时兜底（装配时给 WorkflowOptions）。
 */
public final class OrderActivityOptions {

    private static final Duration DEFAULT_START_TO_CLOSE_TIMEOUT = Duration.ofMinutes(2);

    private OrderActivityOptions() {
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
