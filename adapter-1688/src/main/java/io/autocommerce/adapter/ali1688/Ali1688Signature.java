package io.autocommerce.adapter.ali1688;

import io.autocommerce.core.contract.AdapterException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Map;

/**
 * 1688 param2 网关签名（specs/0005 §5「签名差异 = 平台知识 → Adapter 内实现」）。
 *
 * <p>签名算法（官方「API 签名规则」/「API 调用说明」）：
 * <ol>
 *   <li><b>签名因子一 = urlPath</b>：从协议名 {@code param2} 起、到 {@code "?"} 为止，
 *       即 {@code param2/1/{namespace}/{apiName}/{appKey}}（<b>不含</b> 前导 {@code /openapi}）；</li>
 *   <li><b>签名因子二 = 拼装的参数</b>：请求参数按 <b>key 字典序</b> 排序后，逐项以
 *       {@code key + value} 直接相连（不做 URL 编码、不加分隔符）；</li>
 *   <li>两者拼接为 {@code s}，对 {@code s} 以 App Secret 为密钥做 <b>HMAC-SHA1</b>，
 *       取 <b>大写十六进制</b> 即 {@code _aop_signature}。</li>
 * </ol>
 *
 * <p>参与签名的参数 = 该次请求实际发送的全部参数（含系统级 {@code _aop_timestamp} /
 * {@code access_token} 与业务参数），唯一排除的是 {@code _aop_signature} 自身（自引用）。
 * 文件上传类接口中「文件字节流」参数不参与签名——本 Adapter 不涉及文件上传，故不设例外。
 *
 * <p>另一条网关形态（{@code /openapi/{apiName}/{appKey}?sign=...}，MD5、{@code secret+query+secret}
 * 三段拼接）属<b>旧 api 协议</b>，本 Adapter 一律走 param2（HMAC-SHA1），不实现 MD5 形态。
 * 唯一例外是 OAuth {@code system.oauth2/getToken}：官方明确「调用 getToken 接口不需要签名」。
 */
final class Ali1688Signature {

    /** 签名中必须排除的参数（自引用）。 */
    static final String SIGNATURE_PARAM = "_aop_signature";

    private Ali1688Signature() {
    }

    /** 组装签名因子一：{@code param2/1/{namespace}/{apiName}/{appKey}}（不含前导 {@code /openapi}）。 */
    static String urlPath(String namespace, String apiName, String appKey) {
        return "param2/1/" + namespace + "/" + apiName + "/" + appKey;
    }

    /**
     * 计算 {@code _aop_signature}：{@code uppercase(hex(hmac_sha1(secret, urlPath + sortedKeyValueConcat)))}。
     *
     * @param secret  App Secret（签名密钥）
     * @param urlPath 签名因子一，见 {@link #urlPath(String, String, String)}
     * @param params  该次请求实际发送的全部参数（可含 {@code _aop_signature} 占位，会被排除）
     */
    static String sign(String secret, String urlPath, Map<String, String> params) {
        StringBuilder factor = new StringBuilder(urlPath);
        params.entrySet().stream()
                .filter(entry -> !SIGNATURE_PARAM.equals(entry.getKey()))
                .filter(entry -> entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> factor.append(entry.getKey()).append(entry.getValue()));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return HexFormat.of().withUpperCase()
                    .formatHex(mac.doFinal(factor.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // JDK 必然内置 HmacSHA1——走到这里说明运行环境异常（非平台错误，也非调用方可恢复）。
            // ADR-0007 / specs-0005 §6：能力接口只抛 AdapterException，抛别的 = bug。
            // 归 NON_RETRYABLE：JDK 算法不可用重试无意义，须人工介入。
            throw AdapterException.nonRetryable("sign-failure",
                    "HmacSHA1 不可用（JDK 配置异常）: " + e.getMessage());
        }
    }
}
