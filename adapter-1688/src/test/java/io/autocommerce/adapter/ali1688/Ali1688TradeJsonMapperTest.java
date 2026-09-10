package io.autocommerce.adapter.ali1688;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.contract.AdapterErrorKind;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.DecryptedAddress;
import io.autocommerce.core.contract.dto.LogisticsTrace;
import io.autocommerce.core.contract.dto.PurchaseDraft;
import io.autocommerce.core.contract.dto.PurchaseResult;
import io.autocommerce.core.order.model.Tracking;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易面<b>双向 fixture</b>测试（Testing Decisions §3 硬门槛）：
 * <ul>
 *   <li><b>standard → platform</b>：{@link PurchaseDraft} → {@code cargoParamList} /
 *       {@code addressParam} / {@code tradeWithholdPreparePayParam}，与官方形态 fixture 逐字段比对；</li>
 *   <li><b>platform → standard</b>：下单 / 物流响应 → {@link PurchaseResult} /
 *       {@link LogisticsTrace}，并用 core 契约 ObjectMapper（SNAKE_CASE + NON_NULL）
 *       校核标准侧的序列化形态。</li>
 * </ul>
 *
 * <p>采购面<b>尚无</b> {@code PurchaseResult} 的独立 JSON schema（{@code schemas/} 只有
 * message / order / product-catalog），故该侧以「契约 ObjectMapper 序列化 + 结构断言」校核；
 * 而物流侧的 {@code Tracking} 已是 order schema 的 {@code $defs/Tracking}，<b>直接过 core 契约
 * 校验器</b>（{@code ContractAssertions.assertValid}）。后续补采购 schema 时接入同一门。
 */
class Ali1688TradeJsonMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper CONTRACT_MAPPER = ContractObjectMapper.create();

    private final Ali1688TradeJsonMapper json = new Ali1688TradeJsonMapper(MAPPER);

    @Test
    void cargoParamListMatchesOfficialShape() throws Exception {
        JsonNode expected = MAPPER.readTree(
                        Ali1688TradeSamples.fixture("1688-trade-fastCreateOrder-request.json"))
                .path("cargoParamList");

        String actual = json.cargoParamList(Ali1688TradeSamples.draft().items());

        assertThat(MAPPER.readTree(actual)).isEqualTo(expected);
        // offerId 官方类型为 Long（示例 554456348334）——全数字源 id 必须落成数值节点
        assertThat(MAPPER.readTree(actual).get(0).path("offerId").isNumber()).isTrue();
    }

    @Test
    void addressParamMatchesOfficialShape() throws Exception {
        JsonNode expected = MAPPER.readTree(
                        Ali1688TradeSamples.fixture("1688-trade-fastCreateOrder-request.json"))
                .path("addressParam");

        String actual = json.addressParam(Ali1688TradeSamples.recipient());

        // 官方四级地址传文本名（不需要额外查询地址码）
        assertThat(MAPPER.readTree(actual)).isEqualTo(expected);
    }

    @Test
    void withholdParamMatchesOfficialShape() throws Exception {
        JsonNode actual = MAPPER.readTree(
                json.tradeWithholdPreparePayParam(Ali1688TradeSamples.PURCHASE_NO));

        assertThat(actual.path("orderId").asText()).isEqualTo(Ali1688TradeSamples.PURCHASE_NO);
    }

    @Test
    void missingSpecIdIsNonRetryable() {
        PurchaseDraft.PurchaseDraftItem noSpecId = new PurchaseDraft.PurchaseDraftItem(
                "523681097354", Ali1688TradeSamples.OFFER_ID, null, 1, null);

        assertThatThrownBy(() -> json.cargoParamList(List.of(noSpecId)))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("missing-required-field");
                    assertThat(e.getMessage()).contains("specId");
                });
    }

    @Test
    void missingRecipientFieldIsNonRetryable() {
        assertThatThrownBy(() -> json.addressParam(
                        new DecryptedAddress(
                                null, "13800000000", "CN", "浙江省", "杭州市", "滨江区", "网商路699号", null)))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.getMessage()).contains("fullName");
                });
    }

    @Test
    void orderIdMapsToPurchaseResultInContractShape() throws Exception {
        JsonNode response = MAPPER.readTree(
                Ali1688TradeSamples.fixture("1688-trade-fastCreateOrder-response.json"));

        PurchaseResult result = json.toPurchaseResult(response);

        assertThat(result.platformPurchaseNo()).isEqualTo(Ali1688TradeSamples.PURCHASE_NO);
        // core 契约侧（SNAKE_CASE + 省略 null）：标准模型即对接契约
        assertThat(CONTRACT_MAPPER.writeValueAsString(result))
                .isEqualTo("{\"platform_purchase_no\":\"" + Ali1688TradeSamples.PURCHASE_NO + "\"}");
    }

    @Test
    void missingOrderIdIsNonRetryable() throws Exception {
        JsonNode response = MAPPER.readTree("{\"success\":true,\"code\":\"0\",\"result\":{}}");

        assertThatThrownBy(() -> json.toPurchaseResult(response))
                .isInstanceOfSatisfying(AdapterException.class, e -> {
                    assertThat(e.kind()).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
                    assertThat(e.platformCode()).isEqualTo("missing-order-id");
                });
    }

    @Test
    void logisticsTraceMapsToStandardTrackingInContractShape() throws Exception {
        JsonNode response = MAPPER.readTree(
                Ali1688TradeSamples.fixture("1688-logistics-trace-response.json"));

        LogisticsTrace trace = json.toLogisticsTrace(Ali1688TradeSamples.PURCHASE_NO, response);

        assertThat(trace.platformPurchaseNo()).isEqualTo(Ali1688TradeSamples.PURCHASE_NO);
        assertThat(trace.tracking()).containsExactly(
                new Tracking(null, "3832890717253", "快件已被 已签收 签收", null));
        JsonNode canonical = MAPPER.readTree(CONTRACT_MAPPER.writeValueAsString(trace));
        assertThat(canonical.path("tracking").get(0).path("tracking_no").asText())
                .isEqualTo("3832890717253");
        assertThat(canonical.path("tracking").get(0).path("status").asText())
                .isEqualTo("快件已被 已签收 签收");

        // core 契约校验器：Tracking 是 order.schema.json 的 $defs/Tracking —— 过真正的 schema 门
        for (JsonNode tracking : canonical.path("tracking")) {
            ContractAssertions.assertValid(trackingSchema(), tracking,
                    "1688 物流 → 标准 Tracking 应过 order.schema.json#/$defs/Tracking");
        }
    }

    /** 取 order.schema.json 的 {@code $defs/Tracking}（schemas/ 随 core-contracts test-jar 分发）。 */
    private static JsonSchema trackingSchema() throws Exception {
        JsonNode orderSchema = MAPPER.readTree(
                Ali1688TradeJsonMapperTest.class.getResourceAsStream("/schemas/order.schema.json"));
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(orderSchema.at("/$defs/Tracking"));
    }
}
