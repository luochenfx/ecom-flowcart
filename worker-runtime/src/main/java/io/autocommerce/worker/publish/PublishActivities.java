package io.autocommerce.worker.publish;

import io.autocommerce.publish.PublishDecision;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 铺货链 activity 契约（半步一 activity：落库 + 外部副作用各归其位，ADR-0002）。
 *
 * <p>顺序（由 {@link PublishWorkflowImpl} 确定性编排）：
 * <ol>
 *   <li>{@link #inspect} —— 可重入检查（store 只读），已有 platform_item_id 则幂等命中；</li>
 *   <li>{@link #publish} —— reconcile-first + add；PUBLISHED / AMBIGUOUS 返回决策（并广播事件），
 *       REJECTED / FAILED / RETRYABLE 以 {@link PublishActivityErrors} 类型抛回（映射 Temporal 原语）；</li>
 *   <li>（挂起后 signal 驱动）{@link #confirmPublished} / {@link #reject}；</li>
 *   <li>{@link #recordFailure} —— RETRYABLE 重试耗尽后收口 FAILED。</li>
 * </ol>
 *
 * <p>事件纪律：Domain Event 一律在对应事实<b>落库之后</b>才广播（specs/0016 §0.3：总线永不作 first
 * write），本接口不提供任何"先发事件"的入口。
 */
@ActivityInterface
public interface PublishActivities {

    /** 可重入检查：已有 platform_item_id 的 PUBLISHED 事实 → 幂等命中；不触外部调用。 */
    @ActivityMethod
    PublishDecision inspect(PublishWorkflowInput input);

    /**
     * reconcile-first + add。返回 {@code PUBLISHED} / {@code ALREADY_PUBLISHED} / {@code AMBIGUOUS}
     * 决策（并已落库、广播对应事件）；REJECTED / FAILED / RETRYABLE 分别以不可重试 / 可重试
     * ApplicationFailure 抛回。
     */
    @ActivityMethod
    PublishDecision publish(PublishWorkflowInput input);

    /** AMBIGUOUS 出边 1：人工确认已生效，回填平台引用（落库 + 广播 listing.published）。 */
    @ActivityMethod
    PublishDecision confirmPublished(PublishWorkflowInput input, String platformItemId, String platformItemUrl);

    /** AMBIGUOUS 出边 3：人工判定业务拒绝（落库 REJECTED）。 */
    @ActivityMethod
    PublishDecision reject(PublishWorkflowInput input, String reason);

    /** RETRYABLE 重试耗尽后收口 FAILED（落库 + 结构化原因）。 */
    @ActivityMethod
    PublishDecision recordFailure(PublishWorkflowInput input, String reason);
}
