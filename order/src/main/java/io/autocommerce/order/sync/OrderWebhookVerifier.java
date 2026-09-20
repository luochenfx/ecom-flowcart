package io.autocommerce.order.sync;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * webhook 验签（specs/0003 §5："webhook = 触发信号，拉取 = 数据真相"）。
 *
 * <p>webhook 到达只做两件事：<b>验签</b> → 作为"该渠道需要拉取"的<b>唤醒信号</b>。<b>它不建单、
 * 不做第二写入通道</b>——成交/状态真相一律经 {@link OrderSyncService} 增量拉取；重复 webhook 由
 * 游标推进 + 两级幂等吸收（不产生重复单）。
 *
 * <p>签名 = HMAC-SHA256(secret, rawBody) 的十六进制小写；比较用
 * {@link MessageDigest#isEqual}（常量时间，防时序侧信道）。验签失败抛
 * {@link IllegalArgumentException}——这是安全拒绝（非平台错误），不属 {@code AdapterException}
 * 语义，故不借用它。
 */
public final class OrderWebhookVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public OrderWebhookVerifier(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("webhook secret 必填");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 计算 rawBody 的签名（十六进制小写）；供出网登记 / 测试构造用。 */
    public String sign(String rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("webhook 签名计算失败", e);
        }
    }

    /**
     * 校验 webhook 签名。
     *
     * @throws IllegalArgumentException 签名缺失或不匹配（拒绝该唤醒，不触发拉取）
     */
    public void verify(String rawBody, String signature) {
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("webhook 缺少签名，拒绝唤醒");
        }
        byte[] expected = sign(rawBody).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("webhook 验签失败，拒绝唤醒");
        }
    }
}
