package io.autocommerce.order.address;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.contract.dto.DecryptedAddress;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 收货地址明文加密（specs/0003 §7 / specs/0005 §8）：<b>AES-256-GCM</b>。
 *
 * <p>明文地址只在"下采购单前"存在内存片刻：{@code AddressCapability} 解密取回 →
 * {@link #encrypt(DecryptedAddress)} 加密落库（{@code ShippingAddress.encrypted_payload}）→ 状态置
 * {@code DECRYPTED}。快照里的地址掩码是"下单时平台给的原样"（不可变业务事实），与本节的可后补操作
 * 数据分置，互不污染。
 *
 * <p><b>密钥管理单一职责</b>（specs/0005 §8）：地址解密与渠道凭据加密共用同一加密源。本类只持有
 * 己方密钥（AES 密钥由装配点注入，与凭据加密同源），<b>不</b>产生 / 存储密钥材料；Adapter 侧不持有
 * 密钥（§5）。
 *
 * <p>密文形态 = {@code base64( 12-byte IV ‖ GCM ciphertext+tag )}；每次加密随机 IV。JSON 明文用
 * {@link DecryptedAddress} 自身 snake_case 序列化。
 */
public final class AddressCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper = new ObjectMapper();

    /** @param key AES-256 密钥（恰 32 字节），由装配点从与凭据加密同源的密钥管理注入 */
    public AddressCipher(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("AES-256-GCM 密钥必须为 32 字节");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    /** 明文地址 → 加密负载（base64）。 */
    public String encrypt(DecryptedAddress address) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = mapper.writeValueAsBytes(address);
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("地址加密失败", e);
        }
    }

    /** 加密负载 → 明文地址（仅结算前在内存短暂出现；调用方须避免落日志）。 */
    public DecryptedAddress decrypt(String payload) {
        try {
            byte[] all = Base64.getDecoder().decode(payload);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            byte[] sealed = new byte[all.length - IV_BYTES];
            System.arraycopy(all, IV_BYTES, sealed, 0, sealed.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return mapper.readValue(cipher.doFinal(sealed), DecryptedAddress.class);
        } catch (Exception e) {
            throw new IllegalStateException("地址解密失败（密钥不符或密文损坏）", e);
        }
    }

    /** 便于装配点从配置字符串构造密钥（UTF-8 编解码；长度校验由构造器承担）。 */
    public static AddressCipher fromSecret(String secret) {
        if (secret == null) {
            throw new IllegalArgumentException("地址加密密钥字符串不能为空");
        }
        return new AddressCipher(secret.getBytes(StandardCharsets.UTF_8));
    }
}
