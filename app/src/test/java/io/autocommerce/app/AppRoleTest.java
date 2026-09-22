package io.autocommerce.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AppRole} 单测：证明 {@code app.role} 逗号串被正确 token 化。
 *
 * <p>这是 #74 陷阱 3 的正面证据——{@code @ConditionalOnProperty(havingValue="worker")} 的整串相等
 * 语义在 {@code api,worker,scheduler} 上恒不成立，故必须以本类做子串（token）判定。
 */
class AppRoleTest {

    @Test
    void detectsWorkerInCommaSeparatedRoles() {
        assertThat(AppRole.parse("api,worker,scheduler").hasWorker()).isTrue();
        assertThat(AppRole.parse("api, scheduler, worker").hasWorker()).isTrue();
        assertThat(AppRole.parse("worker").hasWorker()).isTrue();
        assertThat(AppRole.parse(" worker ").hasWorker()).isTrue();
    }

    @Test
    void reportsWithoutWorkerWhenAbsent() {
        assertThat(AppRole.parse("api").hasWorker()).isFalse();
        assertThat(AppRole.parse("api,scheduler").hasWorker()).isFalse();
        assertThat(AppRole.parse("").hasWorker()).isFalse();
        assertThat(AppRole.parse(null).hasWorker()).isFalse();
    }

    @Test
    void wholeStringEqualitySemanticsWouldBeWrong() {
        // @ConditionalOnProperty(havingValue="worker") 是整串相等：逗号串 ≠ "worker" → 恒不成立（实证）
        assertThat("api,worker,scheduler").isNotEqualTo("worker");
        // 正确的 token 判定：
        assertThat(AppRole.parse("api,worker,scheduler").has("worker")).isTrue();
    }

    @Test
    void ignoresBlankTokensAndDeduplicates() {
        assertThat(AppRole.parse("api,,worker, ,api").roles()).containsExactlyInAnyOrder("api", "worker");
    }
}
