package io.autocommerce.adapter.ali1688;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.DecryptedAddress;
import io.autocommerce.core.contract.dto.LogisticsTrace;
import io.autocommerce.core.contract.dto.PurchaseDraft;
import io.autocommerce.core.contract.dto.PurchaseResult;
import io.autocommerce.core.order.model.Tracking;

import java.util.ArrayList;
import java.util.List;

/**
 * 1688 交易面结构转换（specs/0005 §3：结构转换 = Adapter 代码）——
 * <b>standard → platform</b>（{@link PurchaseDraft} → {@code cargoParamList} / {@code addressParam}）
 * 与 <b>platform → standard</b>（下单 / 物流响应 → {@link PurchaseResult} / {@link LogisticsTrace}）。
 *
 * <p>字段形态按官方 apidoc（{@code alibaba.trade.fastCreateOrder-1} /
 * {@code alibaba.trade.cancel-1} / {@code alibaba.trade.getLogisticsTraceInfo.buyerView-1}）校准：
 * <ul>
 *   <li>{@code cargoParamList}（类型 {@code alibaba.trade.fast.cargo[]}）：
 *       {@code [{"offerId": 554456348334, "specId": "b266e0726506185beaf205cbae88530d", "quantity": 5}]}；</li>
 *   <li>{@code addressParam}（类型 {@code alibaba.trade.fast.address}）：
 *       {@code {"address","phone","mobile","fullName","postCode","areaText","townText","cityText","provinceText"}}
 *       ——四级地址传<b>文本名</b>（官方：不需要额外查询地址码）；</li>
 *   <li>物流：顶层 {@code logisticsTrace[]}（{@code logisticsId} / {@code logisticsBillNo} /
 *       {@code logisticsSteps[{acceptTime, remark}]}）。</li>
 * </ul>
 *
 * <p>请求体构造不出来（缺必填项）→ NON_RETRYABLE（不是"值不准"，见契约缺口判定口径）；
 * 响应缺关键字段同样 NON_RETRYABLE（= 形态与文档不符，重试无意义）。
 */
public final class Ali1688TradeJsonMapper {

    private final ObjectMapper mapper;

    public Ali1688TradeJsonMapper() {
        this(new ObjectMapper());
    }

    public Ali1688TradeJsonMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ————————————————— standard → platform —————————————————

    /**
     * 采购草稿行 → {@code cargoParamList} JSON（官方 {@code alibaba.trade.fast.cargo[]}）。
     * 逐行必填 {@code offerId} / {@code specId} / {@code quantity}——缺任一即构造不出请求体。
     */
    public String cargoParamList(List<PurchaseDraft.PurchaseDraftItem> items) {
        if (items == null || items.isEmpty()) {
            throw AdapterException.nonRetryable("missing-cargo", "采购草稿无商品行（cargoParamList 必填）");
        }
        ArrayNode cargo = mapper.createArrayNode();
        for (PurchaseDraft.PurchaseDraftItem item : items) {
            if (item == null) {
                throw AdapterException.nonRetryable("missing-cargo", "采购草稿含空行");
            }
            ObjectNode node = cargo.addObject();
            node.set("offerId", offerIdNode(item.sourceOfferId()));
            node.put("specId", require(item.sourceSpecId(), "specId"));
            if (item.quantity() == null || item.quantity() <= 0) {
                throw AdapterException.nonRetryable("missing-quantity",
                        "采购行 quantity 必须 > 0，实为 " + item.quantity());
            }
            node.put("quantity", item.quantity());
        }
        return cargo.toString();
    }

    /**
     * 明文收货地址 → {@code addressParam} JSON（官方 {@code alibaba.trade.fast.address}）。
     * 官方要求「详细街道地址 ≤200 字」且电话与手机不能同时为空（错误码
     * {@code FAIL_BIZ_RECEIVE_ADDRESS_MOBILE_PHONE_NULL}）——收货人 / 详细地址本仓必填。
     *
     * <p>本仓 {@link DecryptedAddress} 只有一个电话号，落在 {@code mobile}；{@code phone}（固话）无源，
     * 按官方示例可缺省处理（官方示例亦仅示例值）。
     */
    public String addressParam(DecryptedAddress recipient) {
        if (recipient == null) {
            throw AdapterException.nonRetryable("missing-address", "addressParam 必填（收货地址为空）");
        }
        ObjectNode address = mapper.createObjectNode();
        address.put("fullName", require(recipient.receiverName(), "fullName"));
        address.put("mobile", require(recipient.receiverPhone(), "mobile"));
        address.put("provinceText", require(recipient.province(), "provinceText"));
        address.put("cityText", require(recipient.city(), "cityText"));
        address.put("areaText", require(recipient.district(), "areaText"));
        address.put("address", require(recipient.detail(), "address"));
        if (recipient.postalCode() != null && !recipient.postalCode().isBlank()) {
            address.put("postCode", recipient.postalCode());
        }
        return address.toString();
    }

    /** 免密代扣入参 {@code tradeWithholdPreparePayParam}（官方：{@code {"orderId":"订单号"}}）。 */
    public String tradeWithholdPreparePayParam(String platformPurchaseNo) {
        ObjectNode param = mapper.createObjectNode();
        param.put("orderId", require(platformPurchaseNo, "orderId"));
        return param.toString();
    }

    // ————————————————— platform → standard —————————————————

    /**
     * 下单响应 → {@link PurchaseResult}。官方出参 {@code result} 为
     * {@code alibaba.trade.fast.result}，Java SDK 示例取 {@code result.getResult().getOrderId()}。
     * 官方未给出参 JSON 示例——{@code orderId} 以「值节点或数组首元素」两种形态防御性读取，
     * <b>待沙箱真实响应校准</b>（校准点集中在本方法）。
     */
    public PurchaseResult toPurchaseResult(JsonNode body) {
        JsonNode result = body.path("result");
        JsonNode orderId = result.path("orderId");
        if (orderId.isArray()) {
            orderId = orderId.isEmpty() ? null : orderId.get(0);
        }
        if (orderId == null || !orderId.isValueNode() || orderId.asText().isBlank()) {
            throw AdapterException.nonRetryable("missing-order-id",
                    "下单响应缺少 result.orderId（官方形态待沙箱校准）");
        }
        return new PurchaseResult(orderId.asText());
    }

    /**
     * 物流响应 → {@link LogisticsTrace}。官方出参为顶层 {@code logisticsTrace[]}，
     * 每项 {@code {logisticsId, logisticsBillNo, orderId, logisticsSteps[{acceptTime, remark}]}}。
     *
     * <p>标准 {@link Tracking} 的落位：{@code trackingNo ← logisticsBillNo}、
     * {@code status ← 最后一个节点的 remark}（最新节点语义）；
     * {@code company / url} 官方出参<b>未提供</b>（承运商名称不在官方示例字段中），置空不臆造。
     */
    public LogisticsTrace toLogisticsTrace(String platformPurchaseNo, JsonNode body) {
        JsonNode traces = body.path("logisticsTrace");
        List<Tracking> tracking = new ArrayList<>();
        if (traces.isArray()) {
            for (JsonNode trace : traces) {
                JsonNode steps = trace.path("logisticsSteps");
                String status = null;
                if (steps.isArray() && !steps.isEmpty()) {
                    status = steps.get(steps.size() - 1).path("remark").asText(null);
                }
                String trackingNo = trace.path("logisticsBillNo").asText(null);
                tracking.add(new Tracking(null, trackingNo, status, null));
            }
        }
        return new LogisticsTrace(platformPurchaseNo, List.copyOf(tracking));
    }

    // ————————————————— helpers —————————————————

    /**
     * {@code offerId} 官方类型为 Long（示例 {@code 554456348334}）；本仓
     * {@code sourceOfferId} 是字符串（采集面 {@code OfferData.externalId} 同源）。
     * 全数字 → 数值节点（贴合官方类型）；否则文本节点（不让非数字 id 变成非法 JSON 数值）。
     */
    private JsonNode offerIdNode(String sourceOfferId) {
        String offerId = require(sourceOfferId, "offerId");
        return offerId.chars().allMatch(Character::isDigit)
                ? mapper.getNodeFactory().numberNode(Long.parseLong(offerId))
                : mapper.getNodeFactory().textNode(offerId);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw AdapterException.nonRetryable("missing-required-field",
                    "1688 请求体缺少必填字段 " + field + "（cargoParamList/addressParam 逐项必填）");
        }
        return value;
    }
}
