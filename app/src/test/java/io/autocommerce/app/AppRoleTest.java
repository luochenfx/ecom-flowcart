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
    void matchesWorkerOnlyAsExactTokenNotSubstring() {
        // #74 陷阱 3 的变异守卫：真实现按逗号 token 精确匹配 "worker"。若退化为整串子串匹配
        // （如 csv.contains("worker")），下列 token 会被误判为含 worker —— 本断言即令该变异变红。
        assertThat(AppRole.parse("webworker").hasWorker()).isFalse();
        assertThat(AppRole.parse("preworker,postworker").hasWorker()).isFalse();
        assertThat(AppRole.parse("api,scheduler").hasWorker()).isFalse();
        // 对照：整串相等语义（@ConditionalOnProperty 的语义）在逗号串上恒不成立，故必须走 token 化。
        assertThat(AppRole.parse("api,worker,scheduler").hasWorker()).isTrue();
    }

    @Test
    void ignoresBlankTokensAndDeduplicates() {
        assertThat(AppRole.parse("api,,worker, ,api").roles()).containsExactlyInAnyOrder("api", "worker");
    }
}
