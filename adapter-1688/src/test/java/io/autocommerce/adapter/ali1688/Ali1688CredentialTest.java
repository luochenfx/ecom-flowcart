package io.autocommerce.adapter.ali1688;

import io.autocommerce.core.contract.AdapterErrorKind;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.CredentialView;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 1688 凭据解析（specs/0005 §5 键规范落地）：{@link CredentialView} → {@link Ali1688Credential}，
 * 以及回写视图。明文 token 只在本记录内流转，{@code toString} 必须掩码（防落日志）。
 */
class Ali1688CredentialTest {

    @Test
    void parsesDecryptedView() {
        CredentialView view = new CredentialView(Ali1688Credential.TYPE,
                Map.of("app_key", "k", "secret", "s", "access_token", "a", "refresh_token", "r"),
                "2026-09-10T10:00:00Z");

        Ali1688Credential credential = Ali1688Credential.from(view);

        assertThat(credential.appKey()).isEqualTo("k");
        assertThat(credential.appSecret()).isEqualTo("s");
        assertThat(credential.accessToken()).isEqualTo("a");
        assertThat(credential.refreshToken()).isEqualTo("r");
    }

    @Test
    void missingRequiredKeyIsNonRetryable() {
        CredentialView noSecret = new CredentialView(Ali1688Credential.TYPE,
                Map.of("app_key", "k", "access_token", "a"), null);

        assertThatThrownBy(() -> Ali1688Credential.from(noSecret))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("missing-credential");
                    assertThat(e.getMessage()).contains("secret");
                });
    }

    @Test
    void nullViewIsNonRetryable() {
        assertThatThrownBy(() -> Ali1688Credential.from(null))
                .isInstanceOfSatisfying(AdapterException.class, e ->
                        assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE));
    }

    @Test
    void masksSecretsInToString() {
        Ali1688Credential credential = Ali1688TradeSamples.credential();

        String text = credential.toString();

        assertThat(text).contains(Ali1688TradeSamples.APP_KEY);
        assertThat(text).doesNotContain(Ali1688TradeSamples.APP_SECRET)
                .doesNotContain(Ali1688TradeSamples.ACCESS_TOKEN)
                .doesNotContain(Ali1688TradeSamples.REFRESH_TOKEN);
    }

    @Test
    void toCredentialViewCarriesRefreshedTokensAndDropsAbsentRefreshToken() {
        Ali1688Credential credential = Ali1688TradeSamples.credential();

        CredentialView refreshed = credential.toCredentialView("2026-09-10T10:00:00Z",
                "new-access", "new-refresh");
        assertThat(refreshed.secrets()).containsEntry("access_token", "new-access")
                .containsEntry("refresh_token", "new-refresh")
                .containsEntry("app_key", Ali1688TradeSamples.APP_KEY)
                .containsEntry("secret", Ali1688TradeSamples.APP_SECRET);
        assertThat(refreshed.expiresAt()).isEqualTo("2026-09-10T10:00:00Z");

        // 平台不回传 refresh_token 时保留原值（换票接口有的实现不轮换 refresh_token）
        assertThat(credential.toCredentialView(null, "new-access", null).secrets())
                .containsEntry("refresh_token", Ali1688TradeSamples.REFRESH_TOKEN);
    }
}
