package io.autocommerce.adapter.ali1688;

import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.CredentialView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1688 侧凭据（specs/0005 §5 的 CredentialView 键规范落地）。
 *
 * <p><b>只在内存中出现</b>：Channel 域解密密文后以 {@link CredentialView} 交给 Adapter，
 * 本记录是 Adapter 内部对该视图的强类型化，{@code toString} 由 record 默认实现会把密钥
 * 打进日志——故此处<b>不</b>依赖默认 toString，调用方一律不打印本对象（见
 * {@code adapter-1688/README.md}「认证接入」）。
 *
 * <p>键规范（{@code CredentialView.secrets}）：
 * <ul>
 *   <li>{@code app_key} —— App Key（应用标识，同时是签名 urlPath 的末段与 OAuth client_id）</li>
 *   <li>{@code secret} —— App Secret（签名密钥 / OAuth client_secret；<b>严禁硬编码</b>）</li>
 *   <li>{@code access_token} —— 用户授权令牌（调用需授权的接口必备）</li>
 *   <li>{@code refresh_token} —— 可选；仅 {@link Ali1688Auth} 刷新时使用</li>
 * </ul>
 *
 * @param appKey       App Key
 * @param appSecret    App Secret
 * @param accessToken  用户授权令牌
 * @param refreshToken 刷新令牌（可空）
 */
public record Ali1688Credential(String appKey, String appSecret, String accessToken, String refreshToken) {

    /** 凭据类型（与 {@code Credential.type} / {@code CredentialView.type} 对齐）。 */
    public static final String TYPE = "1688";

    static final String KEY_APP_KEY = "app_key";
    static final String KEY_APP_SECRET = "secret";
    static final String KEY_ACCESS_TOKEN = "access_token";
    static final String KEY_REFRESH_TOKEN = "refresh_token";

    public Ali1688Credential {
        appKey = require(appKey, KEY_APP_KEY);
        appSecret = require(appSecret, KEY_APP_SECRET);
        accessToken = require(accessToken, KEY_ACCESS_TOKEN);
    }

    /** 从 Channel 域交来的解密视图取凭据；缺任一必填键 = 配置问题（NON_RETRYABLE，重试无意义）。 */
    public static Ali1688Credential from(CredentialView view) {
        if (view == null) {
            throw AdapterException.nonRetryable("missing-credential",
                    "缺少渠道凭据：1688 Adapter 需要解密后的 CredentialView（type=" + TYPE + "）");
        }
        Map<String, String> secrets = view.secrets() == null ? Map.of() : view.secrets();
        return new Ali1688Credential(secrets.get(KEY_APP_KEY), secrets.get(KEY_APP_SECRET),
                secrets.get(KEY_ACCESS_TOKEN), secrets.get(KEY_REFRESH_TOKEN));
    }

    /** 由本凭据组装 CredentialView（{@link Ali1688Auth#refresh} 的出参形态）。 */
    CredentialView toCredentialView(String expiresAt, String refreshedAccessToken,
                                    String refreshedRefreshToken) {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put(KEY_APP_KEY, appKey);
        secrets.put(KEY_APP_SECRET, appSecret);
        secrets.put(KEY_ACCESS_TOKEN, refreshedAccessToken == null ? accessToken : refreshedAccessToken);
        String token = refreshedRefreshToken == null ? refreshToken : refreshedRefreshToken;
        if (token != null) {
            secrets.put(KEY_REFRESH_TOKEN, token);
        }
        return new CredentialView(TYPE, secrets, expiresAt);
    }

    private static String require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw AdapterException.nonRetryable("missing-credential",
                    "1688 凭据缺少必需键 " + key + "（secrets 键规范见 Ali1688Credential javadoc）");
        }
        return value;
    }

    @Override
    public String toString() {
        return "Ali1688Credential{appKey='" + appKey + "', appSecret=<masked>, "
                + "accessToken=<masked>, refreshToken=<masked>}";
    }
}
