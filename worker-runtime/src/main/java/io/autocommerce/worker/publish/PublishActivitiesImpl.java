package io.autocommerce.worker.publish;

import io.autocommerce.publish.PublishDecision;
import io.autocommerce.publish.PublishDisposition;
import io.autocommerce.publish.PublishService;
import io.autocommerce.worker.event.DomainEvents;
import io.autocommerce.worker.event.EventPublisher;
import io.temporal.failure.ApplicationFailure;

import java.util.Objects;

/**
 * 铺货链 activity 实现：**落库 + 外部副作用**，Domain Event 一律在落库之后广播（specs/0016 §0.3）。
 *
 * <p>业务逻辑不在此处——状态机决策 / 落库 / 调用 {@code PublishCapability} 都在 publish 模块的
 * {@link PublishService} 里（业务模块保持零 Temporal 依赖，禁环②）。本类只做两件事：调服务、
 * 按处置结果映射 Temporal 原语 + 落库后发事件。
 *
 * <p><b>事件 A-prime 降级</b>：落库后、发事件前 crash → 事件丢失；消费端以业务库回读为准、
 * envelope.id 作幂等锚（重复广播由消费端去重）。事务基建（落库与发事件同事务）不在本 slice。
 */
public final class PublishActivitiesImpl implements PublishActivities {

    private final PublishService service;
    private final EventPublisher events;
    private final String workflowType;

    public PublishActivitiesImpl(PublishService service, EventPublisher events, String workflowType) {
        this.service = Objects.requireNonNull(service, "PublishService 必填");
        this.events = Objects.requireNonNull(events, "EventPublisher 必填");
        this.workflowType = Objects.requireNonNull(workflowType, "workflowType 必填");
    }

    @Override
    public PublishDecision inspect(PublishWorkflowInput input) {
        return service.inspect(input.listing());
    }

    @Override
    public PublishDecision publish(PublishWorkflowInput input) {
        PublishDecision decision = service.publish(input.listing());
        emit(decision);
        switch (decision.disposition()) {
            case NEEDS_ADD, ALREADY_PUBLISHED, PUBLISHED, AMBIGUOUS -> {
                return decision;
            }
            case REJECTED -> throw ApplicationFailure.newNonRetryableFailure(
                    decision.reason(), PublishActivityErrors.REJECTED);
            case FAILED -> throw ApplicationFailure.newNonRetryableFailure(
                    decision.reason(), PublishActivityErrors.FAILED);
            case RETRYABLE -> throw ApplicationFailure.newFailure(
                    decision.reason(), PublishActivityErrors.RETRYABLE);
        }
        throw new IllegalStateException("未覆盖的铺货处置: " + decision.disposition());
    }

    @Override
    public PublishDecision confirmPublished(PublishWorkflowInput input, String platformItemId,
                                            String platformItemUrl) {
        PublishDecision decision = service.confirmPublished(input.listingId(), platformItemId, platformItemUrl);
        emit(decision);
        return decision;
    }

    @Override
    public PublishDecision reject(PublishWorkflowInput input, String reason) {
        return service.reject(input.listingId(), reason);
    }

    @Override
    public PublishDecision recordFailure(PublishWorkflowInput input, String reason) {
        return service.fail(input.listingId(), reason);
    }

    /**
     * 落库之后才广播（specs/0016 §0.3）。仅 PUBLISHED / AMBIGUOUS 有对应 Domain Event
     * （listing.published / listing.ambiguous）；REJECTED / FAILED 无领域事件（终态由投影 + Temporal
     * 承载），故不 emit。
     */
    private void emit(PublishDecision decision) {
        if (decision.disposition() == PublishDisposition.PUBLISHED) {
            events.publishDomainEvent(DomainEvents.listingPublished(workflowType, decision.listingId(),
                    decision.platformItemId(), decision.occurredAt()));
        } else if (decision.disposition() == PublishDisposition.AMBIGUOUS) {
            events.publishDomainEvent(DomainEvents.listingAmbiguous(workflowType, decision.listingId(),
                    decision.reason(), decision.occurredAt()));
        }
    }
}
