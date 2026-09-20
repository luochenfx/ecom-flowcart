package io.autocommerce.publish.testsupport;

import io.autocommerce.publish.PublishState;
import io.autocommerce.publish.PublishStateStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 落库故障注入用 {@link PublishStateStore} 装饰器：前 {@code failures} 次 {@link #put} 抛
 * {@link IllegalStateException}（模拟非 {@code AdapterException} 的落库失败），之后透传 delegate。
 *
 * <p>用途：证伪"<b>add 已生效但 PUBLISHED 落库失败</b>被误判为 FAILED"这一缺陷
 * （见 {@code PublishService} 类 javadoc 的"add 成功、落库失败窗口"）——FAILED 会经
 * {@code AllowDuplicateFailedOnly} 授权同 id 新 run → 再次 add → 二次铺货，违反 ADR-0003
 * 「AMBIGUOUS 绝不自动重发」。
 */
public final class FailingPutPublishStateStore implements PublishStateStore {

    private final PublishStateStore delegate;
    private final AtomicInteger remainingFailures;

    public FailingPutPublishStateStore(PublishStateStore delegate, int failures) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 必填");
        if (failures < 0) {
            throw new IllegalArgumentException("failures 不得为负");
        }
        this.remainingFailures = new AtomicInteger(failures);
    }

    @Override
    public Optional<PublishState> get(String listingId) {
        return delegate.get(listingId);
    }

    @Override
    public PublishState put(PublishState state) {
        if (remainingFailures.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
            throw new IllegalStateException("注入的落库失败（FailingPutPublishStateStore）");
        }
        return delegate.put(state);
    }

    @Override
    public List<PublishState> list() {
        return delegate.list();
    }
}
