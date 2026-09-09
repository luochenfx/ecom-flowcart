package io.autocommerce.core.contract;

/**
 * 凭据状态（specs/0005 §5：credentials {type, encrypted_payload, status, expires_at}）。
 * v1 主形态 = 长期 token 人工填入；EXPIRED 驱动看板告警人工刷新。OAuth refresh 平台走 AuthCapability。
 */
public enum CredentialStatus {
    ACTIVE,
    EXPIRED
}
