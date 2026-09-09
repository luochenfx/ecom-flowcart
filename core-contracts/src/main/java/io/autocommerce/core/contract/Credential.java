package io.autocommerce.core.contract;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 渠道凭据存储形态（specs/0005 §5）。DB 中 AES 加密负载落库；
 * 经接口传 {@link CredentialView}（解密内存对象），不传明文 token 字符串（防日志泄漏）。
 *
 * @param type             凭据类型（如 1688-app / oauth2）
 * @param encryptedPayload AES 加密负载
 * @param status           ACTIVE / EXPIRED
 * @param expiresAt        过期时间（ISO-8601，可空 = 长期 token）
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Credential(String type, String encryptedPayload, CredentialStatus status, String expiresAt) {
}
