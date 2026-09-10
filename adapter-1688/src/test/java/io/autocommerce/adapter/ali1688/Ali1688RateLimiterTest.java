package io.autocommerce.adapter.ali1688;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌桶单测（时钟与睡眠可注入 → 确定性，不依赖真实 sleep）。
 *
 * <p>核心语义：超限<b>本地排队</b>（睡到令牌补齐）而不是抛 429——core 不配平台限流参数
 * （ADR-0007：限流自治在 Adapter 内）。
 */
class Ali1688RateLimiterTest {

    @Test
    void firstAcquireConsumesBurstWithoutWaiting() {
        FakeClock clock = new FakeClock();
        Ali1688RateLimiter limiter = new Ali1688RateLimiter(1.0d, 1, clock, clock::sleep);

        limiter.acquire();

        assertThat(clock.slept).isEmpty();
    }

    @Test
    void exceedingRateQueuesLocallyInsteadOfFailing() {
        FakeClock clock = new FakeClock();
        Ali1688RateLimiter limiter = new Ali1688RateLimiter(1.0d, 1, clock, clock::sleep);

        limiter.acquire();
        limiter.acquire();

        // 1 permit/s：第二个请求排队 1s，而不是抛异常
        assertThat(clock.slept).containsExactly(1_000_000_000L);
    }

    @Test
    void permitsRefillWithElapsedTime() {
        FakeClock clock = new FakeClock();
        Ali1688RateLimiter limiter = new Ali1688RateLimiter(2.0d, 2, clock, clock::sleep);

        limiter.acquire();
        limiter.acquire();
        clock.advance(500_000_000L); // 0.5s × 2/s = 补满 1 个令牌
        limiter.acquire();

        assertThat(clock.slept).isEmpty();
    }

    @Test
    void burstIsCappedAtCapacity() {
        FakeClock clock = new FakeClock();
        Ali1688RateLimiter limiter = new Ali1688RateLimiter(10.0d, 2, clock, clock::sleep);

        limiter.acquire();
        limiter.acquire();
        clock.advance(10_000_000_000L); // 静置 10s：桶最多补到 capacity=2，不会无限攒
        limiter.acquire();
        limiter.acquire();
        limiter.acquire();

        assertThat(clock.slept).hasSize(1);
    }

    @Test
    void invalidParametersAreRejected() {
        assertThatThrownBy(() -> new Ali1688RateLimiter(0.0d, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ali1688RateLimiter(1.0d, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeClock implements LongSupplier {

        private final List<Long> slept = new ArrayList<>();
        private long now = 0L;

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long nanos) {
            now += nanos;
        }

        void sleep(long nanos) {
            slept.add(nanos);
        }
    }
}
