package io.autocommerce.adapter.ali1688;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * 1688 侧 token bucket（ADR-0007 / specs/0005 §6：<b>限流自治在 Adapter 内</b>——
 * 平台节奏知识不上 core；超限<b>本地排队</b>而非抛 429，core 不配平台限流参数）。
 *
 * <p>参数默认值取 ADR-0007 记录的「1688 类目 QPS ≤ 10」；平台文档调整时只改这里，
 * 不升 core。
 *
 * <p><b>排队语义</b>：{@link #acquire()} 令牌不足时<b>阻塞等待</b>（不抛异常、不返回失败），
 * 即"把并发削成平台能接受的节奏"。等待在锁内进行——同一 Adapter 实例的调用被串行发牌，
 * 这正是"本地排队"期望的形态（代价是同一时刻只有一个在途调用；需要更高并发时按 QPS 调参，
 * 而不是放开令牌桶）。
 *
 * <p>时钟与睡眠可注入（包内构造器），使节奏断言成为确定性单测而不依赖真实 sleep。
 */
final class Ali1688RateLimiter {

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final double permitsPerSecond;
    private final int capacity;
    private final LongSupplier nanoTime;
    private final LongConsumer sleepNanos;

    /** 当前桶内令牌数（可为小数，按时间连续补充）。 */
    private double storedPermits;
    /** 上次补充令牌的时刻（纳秒）。 */
    private long lastNanos;

    Ali1688RateLimiter(double permitsPerSecond, int capacity) {
        this(permitsPerSecond, capacity, System::nanoTime, nanos -> LockSupport.parkNanos(nanos));
    }

    Ali1688RateLimiter(double permitsPerSecond, int capacity, LongSupplier nanoTime,
                       LongConsumer sleepNanos) {
        if (!(permitsPerSecond > 0)) {
            throw new IllegalArgumentException("permitsPerSecond 必须 > 0，实为 " + permitsPerSecond);
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity 必须 ≥ 1，实为 " + capacity);
        }
        this.permitsPerSecond = permitsPerSecond;
        this.capacity = capacity;
        this.nanoTime = nanoTime;
        this.sleepNanos = sleepNanos;
        this.storedPermits = capacity;
        this.lastNanos = nanoTime.getAsLong();
    }

    /** 取一个令牌；不足则睡到令牌补齐（本地排队，绝不抛 429）。 */
    synchronized void acquire() {
        long now = nanoTime.getAsLong();
        storedPermits = Math.min(capacity,
                storedPermits + (now - lastNanos) / NANOS_PER_SECOND * permitsPerSecond);
        lastNanos = now;

        if (storedPermits >= 1.0d) {
            storedPermits -= 1.0d;
            return;
        }
        // 预支：等满 1 个令牌所需时长，并把 lastNanos 推到等待结束时刻（醒来时桶正好被补空）
        long waitNanos = (long) Math.ceil((1.0d - storedPermits) / permitsPerSecond * NANOS_PER_SECOND);
        storedPermits = 0d;
        lastNanos = now + waitNanos;
        sleepNanos.accept(waitNanos);
    }
}
