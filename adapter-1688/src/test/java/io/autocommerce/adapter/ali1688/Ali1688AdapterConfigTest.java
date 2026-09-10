package io.autocommerce.adapter.ali1688;

import io.autocommerce.core.contract.CredentialView;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapter 装配配置：默认网关 / 限流参数来源、未配置形态、限流调参与入参校验。
 */
class Ali1688AdapterConfigTest {

    @Test
    void unconfiguredHasNoCredentialButKeepsDefaults() {
        Ali1688AdapterConfig config = Ali1688AdapterConfig.unconfigured();

        assertThat(config.configured()).isFalse();
        assertThat(config.gatewayBaseUrl()).isEqualTo(Ali1688AdapterConfig.DEFAULT_GATEWAY_BASE_URL);
        // ADR-0007：1688 类目 QPS ≤ 10；桶容量 = 一秒的量（允许的瞬时突发）
        assertThat(config.permitsPerSecond()).isEqualTo(10.0d);
        assertThat(config.burst()).isEqualTo(10);
    }

    @Test
    void productionAssemblyUsesOfficialGateway() {
        Ali1688AdapterConfig config = Ali1688AdapterConfig.of(Ali1688TradeSamples.credential());

        assertThat(config.configured()).isTrue();
        assertThat(config.gatewayBaseUrl()).isEqualTo("https://gw.open.1688.com");
    }

    @Test
    void assemblesFromDecryptedCredentialView() {
        CredentialView view = new CredentialView(Ali1688Credential.TYPE,
                Map.of("app_key", "k", "secret", "s", "access_token", "a"), null);

        assertThat(Ali1688AdapterConfig.of(view).credential().appKey()).isEqualTo("k");
    }

    @Test
    void rateLimitIsTunableWithoutTouchingCore() {
        Ali1688AdapterConfig tuned = Ali1688AdapterConfig.unconfigured().withRateLimit(5.0d, 3);

        assertThat(tuned.permitsPerSecond()).isEqualTo(5.0d);
        assertThat(tuned.burst()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidRateLimit() {
        assertThatThrownBy(() -> Ali1688AdapterConfig.unconfigured().withRateLimit(0.0d, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ali1688AdapterConfig.unconfigured().withRateLimit(1.0d, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
