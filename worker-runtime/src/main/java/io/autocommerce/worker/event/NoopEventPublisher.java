package io.autocommerce.worker.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link EventPublisher} 的 no-op 默认实现（#20 AC-6）：开发 / 测试 / 装配期 fallback，保留事件
 * 列表供测试断言；不连任何外部总线。生产环境由 #21 publish 提供真实 RabbitMQ 实现替换。
 *
 * <p><b>线程安全</b>：用 {@link CopyOnWriteArrayList} 存储，event 读取天然支持并发（write 极少，
 * read 多——测试断言都走 read）。
 */
public final class NoopEventPublisher implements EventPublisher {

    private final List<SysWorkflowFailedEvent> failed = new CopyOnWriteArrayList<>();

    @Override
    public void publishFailed(SysWorkflowFailedEvent event) {
        failed.add(event);
    }

    /** 测试断言用：已被发布的失败事件快照。 */
    public List<SysWorkflowFailedEvent> publishedFailedEvents() {
        return List.copyOf(failed);
    }
}