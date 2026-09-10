package io.autocommerce.core.contract;

/**
 * 认证能力（可选，specs/0005 §5）。OAuth refresh 等平台专属凭据刷新协议在平台模块内实现；
 * core 只消费"凭据是否可用"（ensureValid），不实现任何平台的刷新协议。
 *
 * <p><b>入 / 出参是 {@link CredentialView}（解密内存对象 + 有效期），不是密文 {@link Credential}</b>：
 * 换 {@code access_token} 需要 App Secret / refresh_token，而它们与被换掉的旧 token 同在
 * {@code encryptedPayload} 里——Adapter 无 AES 密钥，<b>既解不开、也封不回</b>（密钥管理是 Channel 域
 * 单一职责，§5 / §8）。故凭据解密 / 加密由 Channel 域完成，Adapter 只见明文内存对象，与 §5
 * 「Adapter 接口收到的是解密后的内存对象」一致。
 */
public interface AuthCapability extends Capability {

    CredentialView refresh(CredentialView stale) throws AdapterException;
}
