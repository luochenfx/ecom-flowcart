package io.autocommerce.worker.publish;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 铺货 workflow（#21）：每 Listing 一个 {@code listing-{spuId}-{channelId}} 执行（ADR-0003 / specs/0001）。
 *
 * <p>确定性 workflowId = {@link PublishRuntime#workflowIdFor(String, String)}：重复铺货指令 / 重试 /
 * 重放派生同一 execution。终态编码（ADR-0003）：PUBLISHED → completed；REJECTED / FAILED → failed
 * （{@code AllowDuplicateFailedOnly} 复用同 id 重铺）；AMBIGUOUS → <b>挂起</b>（非终态）。
 *
 * <p><b>AMBIGUOUS 挂起下的 signal 契约</b>（specs/0001 §3 状态机图 AMBIGUOUS 的三条出边各一方法）：
 * <ul>
 *   <li>{@link #confirmPublished} —— 人工/对账<b>确认已生效</b>（回填平台引用）→ workflow completed；</li>
 *   <li>{@link #confirmNotEffective} —— 人工确认<b>未生效</b> → 重试 add（再走一次 reconcile-first + add）；</li>
 *   <li>{@link #reject} —— 人工判定<b>业务拒绝</b> → workflow failed（REJECTED）。</li>
 * </ul>
 * 三条出边之外无第四条。signal 承载「对本 Listing 的最终裁定」：workflow 因 AMBIGUOUS 挂起时被消费；
 * 若抢跑于挂起之前到达（如 {@code add} 尚在飞行），该裁定保留至挂起时生效；若该次尝试已收敛
 * （PUBLISHED / 已 failed），则被忽略。裁定无额外副作用，重复投递等价。
 */
@WorkflowInterface
public interface PublishWorkflow {

    /** 跑完"可重入检查 → reconcile-first + add →（歧义则挂起等 signal）"并收敛。 */
    @WorkflowMethod
    PublishWorkflowResult run(PublishWorkflowInput input);

    /** AMBIGUOUS 出边 1：确认已生效，回填平台商品引用 → completed。 */
    @SignalMethod
    void confirmPublished(String platformItemId, String platformItemUrl);

    /** AMBIGUOUS 出边 2：确认未生效 → 重试 add。 */
    @SignalMethod
    void confirmNotEffective();

    /** AMBIGUOUS 出边 3：业务拒绝 → REJECTED（workflow failed）。 */
    @SignalMethod
    void reject(String reason);
}
