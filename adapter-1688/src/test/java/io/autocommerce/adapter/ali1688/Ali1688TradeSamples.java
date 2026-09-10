package io.autocommerce.adapter.ali1688;

import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.contract.dto.DecryptedAddress;
import io.autocommerce.core.contract.dto.PurchaseDraft;
import io.autocommerce.core.order.model.SupplierRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易面测试样本（采购草稿 / 凭据 / fixture 读取 / form 解码）——
 * {@link Ali1688TradeJsonMapperTest} 与 {@link Ali1688PurchaseTest} 共用，避免两处各造一份
 * 而漂移出"fixture 与样本不一致"的假绿。
 */
final class Ali1688TradeSamples {

    static final String OFFER_ID = "6688990011";
    static final String SPEC_ID_BLACK = "b266e0726506185beaf205cbae88530d";
    static final String SPEC_ID_WHITE = "2ba3d63866a71fbae83909d9b4814f01";
    static final String PURCHASE_NO = "988129883123";

    static final String APP_KEY = "test-app-key";
    static final String APP_SECRET = "test-app-secret";
    static final String ACCESS_TOKEN = "test-access-token";
    static final String REFRESH_TOKEN = "test-refresh-token";

    private Ali1688TradeSamples() {
    }

    static Ali1688Credential credential() {
        return new Ali1688Credential(APP_KEY, APP_SECRET, ACCESS_TOKEN, REFRESH_TOKEN);
    }

    /** 两行同 offer 不同规格（官方允许同供应商合单），与 fastCreateOrder 请求 fixture 一致。 */
    static PurchaseDraft draft() {
        return draft(SPEC_ID_BLACK, SPEC_ID_WHITE);
    }

    /** 单行草稿；{@code specId} 同时用作 WireMock 的用例标识（按请求体分流）。 */
    static PurchaseDraft draft(String specId) {
        return new PurchaseDraft(
                new SupplierRef("1688", "seller-10086", "某某数码供应商"),
                List.of(new PurchaseDraft.PurchaseDraftItem("523681097354", OFFER_ID, specId, 2,
                        new Money("45.9", "CNY"))),
                recipient());
    }

    private static PurchaseDraft draft(String firstSpecId, String secondSpecId) {
        return new PurchaseDraft(
                new SupplierRef("1688", "seller-10086", "某某数码供应商"),
                List.of(
                        new PurchaseDraft.PurchaseDraftItem("523681097354", OFFER_ID, firstSpecId, 2,
                                new Money("45.9", "CNY")),
                        new PurchaseDraft.PurchaseDraftItem("523681097355", OFFER_ID, secondSpecId, 1,
                                new Money("45.9", "CNY"))),
                recipient());
    }

    static DecryptedAddress recipient() {
        return new DecryptedAddress("张三", "13800000000", "CN", "浙江省", "杭州市", "滨江区",
                "网商路699号", "310000");
    }

    /** 读取 {@code src/test/resources/fixtures/<name>}。 */
    static String fixture(String name) {
        try (InputStream in = Ali1688TradeSamples.class
                .getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture 缺失: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** WireMock 收到的 {@code application/x-www-form-urlencoded} 请求体 → 解码后的参数表。 */
    static Map<String, String> formOf(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return params;
    }
}
