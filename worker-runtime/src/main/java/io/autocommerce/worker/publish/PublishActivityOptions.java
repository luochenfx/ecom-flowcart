package io.autocommerce.worker.publish;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;

import java.time.Duration;

/**
 * 铺货链 activity 调用选项（Composition Root 装配参数，非业务语义）。
 *
 * <p>重试参数取 specs/0001 §6 的<b>起步默认</b>（平台级调优 out of scope，随真实 Adapter 校准）：
 * <ul>
 *   <li>{@code MaximumAttempts ≈ 5}：限流/5xx/抖动最多 5 次；</li>
 *   <li>指数退避 {@code 1s × 2}（{@code backoffCoefficient=2.0}，上限 30s 防无限拉长）；</li>
 *   <li>{@code ScheduleToCloseTimeout} 兜底（10 分钟，含所有重试的总时长上限）；</li>
 *   <li>{@code retryableAfter} 供退避参考——由 Adapter 在 {@code AdapterException} 上携带，真实
 *       Adapter 可按平台 Retry-After 覆盖 {@code initialInterval}（本类给保守起步值）。</li>
 * </ul>
 *
 * <p><b>不可重试名单</b>（{@code doNotRetry}）：REJECTED（业务拒绝）与 FAILED（意外未知）——
 * 对应 Adapter NON_RETRYABLE 与非 AdapterException 缺陷（specs/0005 §6：不得把业务拒绝 / 代码缺陷
 * 当临时故障重试到死）。二者亦以非重试 ApplicationFailure 抛出（双保险）。
 *
 * <p>AMBIGUOUS 不在此名单：它不以失败抛出，而是 activity 正常返回 {@code AMBIGUOUS} 决策
 * （落库后由 workflow 挂起）——故绝不被退避重发（specs/0001 §5）。
 *
 * <h2>为什么 AC-4 的「NON_RETRYABLE → … + Saga」在本票判为 N/A（显式登记，非漏做）</h2>
 * issue #21 AC-4 原文与 specs/0001 §5 映射表 / specs/0005 §6 同措辞为「{@code NonRetryableErrorTypes}
 * <b>+ Saga 补偿</b>」。本票只落地 {@code NonRetryableErrorTypes}（即 {@code setDoNotRetry(REJECTED,
 * FAILED)} 这一行），Saga 补偿<b>显式登记为不适用</b>，理由如下：
 * <ul>
 *   <li>本票 publish workflow 的<b>唯一外部副作用是 {@code add}</b>（一次原子外部调用，见
 *       {@code PublishService#publish}）；</li>
 *   <li>{@code add} 抛 NON_RETRYABLE 的语义 = 平台<b>业务拒绝、明确未生效</b>（REJECTED）——这区别于
 *       AMBIGUOUS 的"不知是否生效"。既然该调用未在平台侧产生任何已提交的中间状态，就<b>没有需要
 *       回滚/补偿的前置步骤</b>，Saga 在"单步外部调用"场景下无补偿对象；</li>
 *   <li>真正需要 Saga 补偿的场景，要等 {@code add} <b>之后</b>还有后续已提交步骤（例如回填平台侧媒体
 *       {@code platform_media_id}、或第二次平台侧写调用）才会出现——那属于后续 slice。</li>
 * </ul>
 *
 * <p>另需区分：NON_RETRYABLE 落的是 {@code REJECTED}（见 {@code PublishFailure.classify}），不是
 * FAILED——FAILED 只收口"可重试耗尽 / 非 AdapterException 缺陷"。
 */
public final class PublishActivityOptions {

    private static final Duration START_TO_CLOSE_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration SCHEDULE_TO_CLOSE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration INITIAL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_INTERVAL = Duration.ofSeconds(30);
    private static final double BACKOFF_COEFFICIENT = 2.0;
    private static final int MAXIMUM_ATTEMPTS = 5;

    private PublishActivityOptions() {
    }

    /** v1 默认 activity 选项（见类 javadoc）。 */
    public static ActivityOptions defaults() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(START_TO_CLOSE_TIMEOUT)
                .setScheduleToCloseTimeout(SCHEDULE_TO_CLOSE_TIMEOUT)
                .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(INITIAL_INTERVAL)
                        .setBackoffCoefficient(BACKOFF_COEFFICIENT)
                        .setMaximumInterval(MAXIMUM_INTERVAL)
                        .setMaximumAttempts(MAXIMUM_ATTEMPTS)
                        .setDoNotRetry(PublishActivityErrors.REJECTED, PublishActivityErrors.FAILED)
                        .build())
                .build();
    }
}
