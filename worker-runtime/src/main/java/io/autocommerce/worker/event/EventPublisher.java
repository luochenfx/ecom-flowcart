package io.autocommerce.worker.event;

import io.autocommerce.core.message.Envelope;

/**
 * 业务事件发布端口（#20 AC-6 / specs/0006 §5）。本接口是 worker-runtime 的 seam：
 * <ul>
 *   <li>v1 默认实现 = {@link NoopEventPublisher}（开发 / 测试 / 装配期 fallback）；</li>
 *   <li>#21 publish 引入真实 RabbitMQ publisher 时换实现即可，activity 构造器签名不变。</li>
 * </ul>
 *
 * <p><b>不暴露通用 publish(Object)</b>：刻意收窄到两个语义明确的方法，避免 activity 层"想发啥就发啥"
 * 导致契约蔓延：
 * <ul>
 *   <li>{@link #publishFailed(SysWorkflowFailedEvent)} —— lifecycle 失败信号；</li>
 *   <li>{@link #publishDomainEvent(Envelope)} —— <b>已落库事实</b>的 Domain Event（specs/0016 §0.3：
 *       总线只承载已落库事实的广播，<b>永不作 first write</b>）。入参是 envelope 契约本身
 *       （{@link Envelope}），不是裸 Object，type/version/payload 由调用方按 repo registry 给定。</li>
 * </ul>
 */
public interface EventPublisher {

    /** 发送 {@code sys.workflow.failed} 事件（A-prime 降级：可能丢失，看板需配合 Temporal UI 巡检）。 */
    void publishFailed(SysWorkflowFailedEvent event);

    /**
     * 广播一条 Domain Event（业务事实已落库后，specs/0016 §0.3）。
     *
     * <p>A-prime 降级语义同 {@link #publishFailed}：process 在落库后、发事件前 crash → 事件丢失；
     * 消费端以业务库回读为准，envelope.id 作幂等锚（重复广播由消费端去重）。
     *
     * <p><b>envelope.id 由事实键确定性派生</b>（见 {@link DomainEvents}）：同一已落库事实的重复广播
     * （activity 失败重跑 / broker 重投）产出<b>同一</b> id，故该幂等锚对"重跑产生第二条 envelope"
     * 同样有效，而非仅对 broker 重投同一 envelope 有效。
     */
    void publishDomainEvent(Envelope envelope);
}