package io.autocommerce.adapter.ali1688;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1688 param2 签名单测（无网络、确定性）。
 *
 * <p>断言的是<b>规则本身</b>：签名因子一 = {@code param2/1/{namespace}/{apiName}/{appKey}}
 * （不含前导 {@code /openapi}）；因子二 = 参数按 key 字典序 {@code key+value} 直接相连；
 * 结果 = HMAC-SHA1 的<b>大写</b>十六进制；{@code _aop_signature} 自身不参与签名。
 */
class Ali1688SignatureTest {

    private static final String SECRET = "test-app-secret";
    private static final String URL_PATH =
            Ali1688Signature.urlPath("com.alibaba.trade", "alibaba.trade.cancel", "test-app-key");

    @Test
    void urlPathStartsAtProtocolSegment() {
        // 从协议段 param2 起、到 "?" 止；不含前导 /openapi
        assertThat(URL_PATH)
                .isEqualTo("param2/1/com.alibaba.trade/alibaba.trade.cancel/test-app-key");
        assertThat(URL_PATH).doesNotStartWith("/");
    }

    @Test
    void signConcatenatesSortedKeyValuesBehindUrlPath() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("webSite", "1688");
        params.put("cancelReason", "other");
        params.put("access_token", "tok");

        String signature = Ali1688Signature.sign(SECRET, URL_PATH, params);

        // 期望因子串：urlPath + access_tokentok + cancelReasonother + webSite1688（字典序）
        String expectedFactor = URL_PATH + "access_tokentok" + "cancelReasonother" + "webSite1688";
        assertThat(signature).isEqualTo(hmacSha1Upper(SECRET, expectedFactor));
        assertThat(signature).matches("[0-9A-F]{40}");
    }

    @Test
    void signIsOrderIndependent() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("b", "2");
        first.put("a", "1");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("a", "1");
        second.put("b", "2");

        assertThat(Ali1688Signature.sign(SECRET, URL_PATH, first))
                .isEqualTo(Ali1688Signature.sign(SECRET, URL_PATH, second));
    }

    @Test
    void signatureParamIsExcludedFromItsOwnSigning() {
        Map<String, String> withPlaceholder = new LinkedHashMap<>();
        withPlaceholder.put("a", "1");
        withPlaceholder.put(Ali1688Signature.SIGNATURE_PARAM, "PLACEHOLDER");
        Map<String, String> without = new LinkedHashMap<>();
        without.put("a", "1");

        assertThat(Ali1688Signature.sign(SECRET, URL_PATH, withPlaceholder))
                .isEqualTo(Ali1688Signature.sign(SECRET, URL_PATH, without));
    }

    @Test
    void differentSecretYieldsDifferentSignature() {
        Map<String, String> params = Map.of("a", "1");

        assertThat(Ali1688Signature.sign(SECRET, URL_PATH, params))
                .isNotEqualTo(Ali1688Signature.sign("another-secret", URL_PATH, params));
    }

    private static String hmacSha1Upper(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return HexFormat.of().withUpperCase()
                .formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
