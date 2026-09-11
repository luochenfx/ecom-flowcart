package io.autocommerce.worker.event;

/**
 * 业务事件发布端口（#20 AC-6 / specs/0006 §5）。本接口是 worker-runtime 的 seam：
 * <ul>
 *   <li>v1 默认实现 = {@link NoopEventPublisher}（开发 / 测试 / 装配期 fallback）；</li>
 *   <li>#21 publish 引入真实 RabbitMQ publisher 时换实现即可，{@code ContentChainActivitiesImpl}
 *       构造器签名不变。</li>
 * </ul>
 *
 * <p><b>不暴露通用 publish(Object)</b>：刻意收窄到 {@link #publishFailed(SysWorkflowFailedEvent)}，
 * 避免 activity 层"想发啥就发啥"导致契约蔓延。后续 lifecycle 事件（如 {@code sys.workflow.started}）
 * 落地时再加方法。
 */
public interface EventPublisher {

    /** 发送 {@code sys.workflow.failed} 事件（A-prime 降级：可能丢失，看板需配合 Temporal UI 巡检）。 */
    void publishFailed(SysWorkflowFailedEvent event);
}