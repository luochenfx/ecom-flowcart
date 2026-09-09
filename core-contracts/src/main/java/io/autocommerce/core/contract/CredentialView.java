package io.autocommerce.core.contract;

import java.util.Map;

/**
 * CredentialView（解密后的内存凭据，specs/0005 §5）。
 * Adapter 收到的是解密后的键值对 + 有效期；明文 token 不进接口、不进日志。
 * 键规范按凭据类型约定（如 type=1688 → app_key/secret；type=oauth2 → access_token/refresh_token）。
 * toString 显式掩码，防凭据值落日志。
 *
 * @param type      凭据类型（与 {@link Credential#type()} 一致）
 * @param secrets   解密后的凭据键值
 * @param expiresAt 过期时间（ISO-8601，可空 = 长期 token）
 */
public record CredentialView(String type, Map<String, String> secrets, String expiresAt) {

    @Override
    public String toString() {
        return "CredentialView{type='" + type + "', secrets=<masked>, expiresAt=" + expiresAt + '}';
    }
}
