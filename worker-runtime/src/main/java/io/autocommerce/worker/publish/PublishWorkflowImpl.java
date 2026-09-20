package io.autocommerce.worker.publish;

import io.autocommerce.publish.PublishDecision;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

/**
 * 铺货链 workflow 实现（确定性编排壳）：自身不做任何 I/O / 时钟 / 随机数——所有副作用在 activity 侧
 * （{@link PublishActivitiesImpl}），这是 Temporal 重放的前提（与 {@code OrderWorkflowImpl} /
 * {@code ContentWorkflowImpl} 同例）。
 *
 * <p>业务链：<b>可重入检查</b>（{@link PublishActivities#inspect}）→ <b>reconcile-first + add</b>
 * （{@link PublishActivities#publish}）→（{@code AMBIGUOUS} / {@code SUSPENDED_UNRECORDED} 则挂起等
 * signal）→ 收敛。
 *
 * <p>失败分流（AC-4）：
 * <ul>
 *   <li>{@code REJECTED} / {@code FAILED}（不可重试 ApplicationFailure）——服务已落库终态，
 *       直接抛出 → workflow failed（{@code AllowDuplicateFailedOnly} 放行重铺）；</li>
 *   <li>{@code RETRYABLE}（可重试）——由 Activity RetryPolicy 退避重试；<b>耗尽</b>后落到本方法
 *       的 catch：先 {@link PublishActivities#recordFailure} 收口 FAILED（落库），再抛出 → failed。</li>
 * </ul>
 */
public final class PublishWorkflowImpl implements PublishWorkflow {

    private final PublishActivities activities =
            Workflow.newActivityStub(PublishActivities.class, PublishActivityOptions.defaults());

    /**
     * AMBIGUOUS 挂起期间由 signal handler 写入；{@link #run} 的 await 谓词读它。
     *
     * <p><b>清空时机</b>：只在<b>消费掉</b>一次裁定之后置空（见 {@code run} 的 {@code decided} 之后），
     * <b>不</b>在每次尝试前置空——否则在首个 {@code inspect} 期间赶到的 signal 会在进入循环时被丢弃。
     * 如此"早到即保留"成立：{@code inspect} / {@code add} 尚在飞行期间到达的裁定都保留到挂起时生效。
     */
    private Resolution resolution;

    @Override
    public PublishWorkflowResult run(PublishWorkflowInput input) {
        PublishDecision inspected = activities.inspect(input);
        if (inspected.published()) {
            return toResult(inspected);
        }
        while (true) {
            PublishDecision attempt = attemptPublish(input);
            if (attempt.published()) {
                return toResult(attempt);
            }
            // AMBIGUOUS / SUSPENDED_UNRECORDED：非终态，挂起等人工/对账 signal（specs/0001 §3；重复 start 仍 AlreadyStarted）
            Workflow.await(() -> resolution != null);
            Resolution decided = resolution;
            resolution = null; // 消费后置空：下一轮 await 只认新到的 signal
            switch (decided.kind()) {
                case CONFIRMED_PUBLISHED -> {
                    return toResult(activities.confirmPublished(input, decided.platformItemId(),
                            decided.platformItemUrl()));
                }
                case CONFIRMED_NOT_EFFECTIVE -> {
                    // 重走 reconcile-first + add（见 PublishService 类 javadoc 的路径 (a)/(b)）
                }
                case REJECTED -> {
                    activities.reject(input, decided.reason());
                    throw ApplicationFailure.newNonRetryableFailure(decided.reason(),
                            PublishActivityErrors.REJECTED);
                }
            }
        }
    }

    private PublishDecision attemptPublish(PublishWorkflowInput input) {
        try {
            return activities.publish(input);
        } catch (ActivityFailure failure) {
            if (isRetryable(failure)) {
                // 可重试类重试耗尽：收口 FAILED（落库后再抛出，specs/0001 §3 状态表）
                activities.recordFailure(input, messageOf(failure));
            }
            throw failure;
        }
    }

    private static boolean isRetryable(Throwable failure) {
        ApplicationFailure application = findApplicationFailure(failure);
        return application != null && PublishActivityErrors.RETRYABLE.equals(application.getType());
    }

    private static String messageOf(Throwable failure) {
        ApplicationFailure application = findApplicationFailure(failure);
        if (application != null && application.getMessage() != null) {
            return PublishActivityErrors.RETRYABLE + ": " + application.getMessage();
        }
        return failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
    }

    private static ApplicationFailure findApplicationFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApplicationFailure application) {
                return application;
            }
        }
        return null;
    }

    // signal handler：写入本次挂起的裁定（见 PublishWorkflow javadoc 的「消费时机」）。

    @Override
    public void confirmPublished(String platformItemId, String platformItemUrl) {
        resolution = new Resolution(ResolutionKind.CONFIRMED_PUBLISHED, platformItemId, platformItemUrl, null);
    }

    @Override
    public void confirmNotEffective() {
        resolution = new Resolution(ResolutionKind.CONFIRMED_NOT_EFFECTIVE, null, null, null);
    }

    @Override
    public void reject(String reason) {
        resolution = new Resolution(ResolutionKind.REJECTED, null, null, reason);
    }

    private static PublishWorkflowResult toResult(PublishDecision decision) {
        return new PublishWorkflowResult(decision.listingId(), decision.platformItemId(),
                decision.platformItemUrl(), decision.occurredAt(), decision.reused());
    }

    /** AMBIGUOUS 挂起的三条出边（与 {@link PublishWorkflow} 的 signal 方法一一对应）。 */
    private enum ResolutionKind {
        CONFIRMED_PUBLISHED,
        CONFIRMED_NOT_EFFECTIVE,
        REJECTED
    }

    /** 挂起决策（workflow 内部类型，不跨执行边界）。 */
    private record Resolution(ResolutionKind kind, String platformItemId, String platformItemUrl, String reason) {
    }
}
