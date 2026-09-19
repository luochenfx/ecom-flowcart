package io.autocommerce.adapter.ali1688;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.autocommerce.core.contract.AdapterErrorKind;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.DecryptedAddress;
import io.autocommerce.core.contract.dto.LogisticsTrace;
import io.autocommerce.core.contract.dto.PurchaseDraft;
import io.autocommerce.core.contract.dto.PurchaseResult;
import io.autocommerce.core.order.model.Tracking;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SpecVersion;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractObjectMapper;
import io.autocommerce.core.testutil.ContractSchemas;
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
 * <p>采购面<b>无</b> {@code PurchaseResult} 的独立 JSON schema（{@code schemas/} 只有
 * message / order / product-catalog；#54 D2 决议：Adapter 自有 DTO 不给自己造 schema），故 DTO 层以
 * 「契约 ObjectMapper 序列化 + 结构断言」校核；标准侧则把 adapter 真实产出
 * （{@code platform_purchase_no} / {@code platform_raw}，其余必填字段取自 core golden fixture）
 * 落领域 {@code PurchaseOrder} 形态，过 {@code order.schema.json#/$defs/PurchaseOrder} 门
 * （含 {@code platform_raw}）。{@code platformRaw} 逃生口（#46）即以此锁住
 * 「<b>单节点</b> / 未映射字段直通 / 可空省略」。而物流侧的
 * {@code Tracking} 已是 order schema 的 {@code $defs/Tracking}，<b>直接过 core 契约校验器</b>
 * （{@code ContractAssertions.assertValid}）。
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
        JsonNode canonical = CONTRACT_MAPPER.valueToTree(result);
        assertThat(canonical.path("platform_purchase_no").asText())
                .isEqualTo(Ali1688TradeSamples.PURCHASE_NO);

        // platformRaw 逃生口（#46）：fastCreateOrder result 子树原文直通，未映射字段不丢
        JsonNode raw = result.platformRaw();
        assertThat(raw).isNotNull();
        assertThat(raw.isObject()).as("platformRaw 应为单节点 JSON 对象（非数组）").isTrue();
        assertThat(raw.path("totalAmount").asText()).isEqualTo("91.80");
        assertThat(raw.path("freight").asText()).isEqualTo("0.00");
        assertThat(raw.path("flowaprroveUrl").asText())
                .isEqualTo("https://trade.1688.com/order/flow_approve.htm?orderId="
                        + Ali1688TradeSamples.PURCHASE_NO);
        assertThat(raw.path("gmtCreate").asText()).isEqualTo("2026-09-18 10:00:00");
        assertThat(raw.path("gmtModified").asText()).isEqualTo("2026-09-18 10:00:05");
        assertThat(raw.path("status").asText()).isEqualTo("waitbuyerpay");

        // 标准侧序列化形态：platform_raw 同一单节点直通
        assertThat(canonical.path("platform_raw").isObject()).isTrue();
        assertThat(canonical.path("platform_raw").path("totalAmount").asText()).isEqualTo("91.80");
    }

    @Test
    void platformRawOmittedWhenAbsentInContractShape() throws Exception {
        // 逃生口可空：raw 为空 → 契约 NON_NULL 序列化省略 platform_raw（schema 视为可省略）
        PurchaseResult bare = new PurchaseResult(Ali1688TradeSamples.PURCHASE_NO, null);

        assertThat(CONTRACT_MAPPER.writeValueAsString(bare))
                .isEqualTo("{\"platform_purchase_no\":\"" + Ali1688TradeSamples.PURCHASE_NO + "\"}");
    }

    @Test
    void purchaseResultFieldsPassPurchaseOrderDefSchemaGate() throws Exception {
        // adapter 真实产出：fastCreateOrder 响应 → PurchaseResult
        JsonNode response = MAPPER.readTree(
                Ali1688TradeSamples.fixture("1688-trade-fastCreateOrder-response.json"));
        PurchaseResult result = json.toPurchaseResult(response);
        assertThat(result.platformRaw())
                .as("前置：fastCreateOrder 的 platformRaw 应为单节点 JSON 对象（非数组/标量）")
                .isNotNull();
        assertThat(result.platformRaw().isObject()).isTrue();

        JsonSchema purchaseOrder = purchaseOrderDef();

        // ① platformRaw 存在（对象）：断言的 subject 仅是 adapter 的 platform_purchase_no +
        //    platform_raw；其余必填字段取自 core golden fixture purchase_orders[0]（真实 golden 文档，
        //    非手捏占位），故占位字段不会成为断言强度来源。
        ObjectNode withRaw = purchaseOrderDoc();
        withRaw.put("platform_purchase_no", result.platformPurchaseNo());
        withRaw.set("platform_raw", result.platformRaw());
        ContractAssertions.assertValid(purchaseOrder, withRaw,
                "1688 采购响应产出（platform_raw 为对象）→ 领域 PurchaseOrder 应过 "
                        + "order.schema.json#/$defs/PurchaseOrder");

        // ② platformRaw 为 null：RawJson = ["object","null"]，且 platform_raw 不在 required
        // ③ platformRaw 缺失：契约 NON_NULL 序列化省略该字段 —— 逃生口为空同样必须过门
        PurchaseResult bare = new PurchaseResult(Ali1688TradeSamples.PURCHASE_NO, null);

        ObjectNode explicitNull = purchaseOrderDoc();
        explicitNull.put("platform_purchase_no", bare.platformPurchaseNo());
        explicitNull.putNull("platform_raw");
        ContractAssertions.assertValid(purchaseOrder, explicitNull,
                "1688 采购响应产出（platform_raw 为 null）→ 领域 PurchaseOrder 应过 "
                        + "order.schema.json#/$defs/PurchaseOrder");

        ObjectNode omitted = purchaseOrderDoc();
        omitted.put("platform_purchase_no", bare.platformPurchaseNo());
        ContractAssertions.assertValid(purchaseOrder, omitted,
                "1688 采购响应产出（platform_raw 省略）→ 领域 PurchaseOrder 应过 "
                        + "order.schema.json#/$defs/PurchaseOrder");
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

    /**
     * 取 order.schema.json 的 {@code $defs/PurchaseOrder} 子 schema。
     *
     * <p>与物流侧 {@code $defs/Tracking} 不同：{@code PurchaseOrder} 内含 {@code #/$defs/*} 内部引用
     * （SupplierRef / PurchaseStatus / Money / Tracking / Timestamps / PurchaseLine / RawJson，及跨文件
     * Provenance），直接 {@code at("/$defs/PurchaseOrder")} 抽子树会让这些引用悬空（networknt 报
     * {@code Reference /$defs/SupplierRef cannot be resolved}）。故改经 {@link ContractSchemas#order()}
     * （跨文件 Provenance 已内联的整文档 schema）以 {@code getSubSchema} 取子 schema ——
     * 引用仍对整文档根解析，约束不变。
     */
    private static JsonSchema purchaseOrderDef() {
        return ContractSchemas.order().getSubSchema(
                new JsonNodePath(PathType.JSON_POINTER).append("$defs").append("PurchaseOrder"));
    }

    /**
     * 领域 PurchaseOrder 文档：非 adapter 来源的必填字段取自 core golden fixture
     * {@code /fixtures/order.json} 的 {@code purchase_orders[0]}（随 core-contracts test-jar 分发）。
     */
    private static ObjectNode purchaseOrderDoc() throws Exception {
        return (ObjectNode) MAPPER.readTree(Ali1688TradeSamples.fixture("order.json"))
                .path("purchase_orders").get(0);
    }
}
