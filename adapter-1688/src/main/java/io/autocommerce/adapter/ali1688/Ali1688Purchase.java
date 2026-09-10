package io.autocommerce.adapter.ali1688;

import com.fasterxml.jackson.databind.JsonNode;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.PurchaseCapability;
import io.autocommerce.core.contract.dto.LogisticsTrace;
import io.autocommerce.core.contract.dto.PurchaseDraft;
import io.autocommerce.core.contract.dto.PurchaseResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1688 PurchaseCapability 实现（specs/0005 §2 / §9.1，#23）。
 *
 * <p>四段独立调用，端点与参数按官方 apidoc 校准：
 * <ol>
 *   <li><b>下单</b> {@code alibaba.trade.fastCreateOrder}：{@code flow=saleproxy}（一件代发；
 *       {@code general} 为普通批发）+ {@code cargoParamList} + {@code addressParam}；
 *       官方「仅同供应商可合单」——跨供应商须由 order 域拆单后逐单调用；</li>
 *   <li><b>支付</b> {@code alibaba.trade.pay.protocolPay.preparePay}（免密代扣）；
 *       未开通免密时平台返回签约 / 收银台链接，<b>契约返回 void 无法回传</b>——
 *       是否需要把支付链接回传属契约扩展（ADR-0007 走加法），本票不改 core；</li>
 *   <li><b>撤销</b> {@code alibaba.trade.cancel}：<b>仅未付款可撤</b>（已付款须走售后退款）；
 *       {@code cancelReason} 官方取值 {@code buyerCancel / sellerGoodsLack / other}，
 *       契约无"原因"入参 → 固定 {@code other}；</li>
 *   <li><b>物流</b> {@code alibaba.trade.getLogisticsTraceInfo.buyerView}（买家视角，需申请权限）。</li>
 * </ol>
 *
 * <p><b>不实现 {@code ShipmentCapability}</b>：其语义是「供应商物流单号 → 销售平台」，
 * 携带销售平台订单号，只属销售平台侧 Adapter（见 ADR-0007 归属侧别纠偏）。
 */
public final class Ali1688Purchase implements PurchaseCapability {

    /** 一件代发（代销）下单通道；官方 flow 枚举：general / fenxiao / saleproxy / paired / repurchase… */
    static final String FLOW_SALEPROXY = "saleproxy";

    /** 官方 cancelReason 取值之一；契约 cancelPurchase 无"原因"入参，固定为 other。 */
    static final String CANCEL_REASON_OTHER = "other";

    static final String PARAM_FLOW = "flow";
    static final String PARAM_CARGO_PARAM_LIST = "cargoParamList";
    static final String PARAM_ADDRESS_PARAM = "addressParam";
    static final String PARAM_TRADE_ID = "tradeID";
    static final String PARAM_CANCEL_REASON = "cancelReason";
    static final String PARAM_ORDER_ID = "orderId";
    static final String PARAM_WITHHOLD_PARAM = "tradeWithholdPreparePayParam";

    private final Ali1688Gateway gateway;
    private final Ali1688TradeJsonMapper json;

    public Ali1688Purchase(Ali1688AdapterConfig config) {
        this(new Ali1688Gateway(config));
    }

    Ali1688Purchase(Ali1688Gateway gateway) {
        this(gateway, new Ali1688TradeJsonMapper());
    }

    Ali1688Purchase(Ali1688Gateway gateway, Ali1688TradeJsonMapper json) {
        this.gateway = gateway;
        this.json = json;
    }

    @Override
    public PurchaseResult createPurchase(PurchaseDraft draft) throws AdapterException {
        if (draft == null) {
            throw AdapterException.nonRetryable("missing-draft", "PurchaseDraft 为空");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put(PARAM_FLOW, FLOW_SALEPROXY);
        params.put(PARAM_CARGO_PARAM_LIST, json.cargoParamList(draft.items()));
        params.put(PARAM_ADDRESS_PARAM, json.addressParam(draft.recipient()));
        JsonNode body = gateway.call(Ali1688Api.TRADE_FAST_CREATE_ORDER, params);
        return json.toPurchaseResult(body);
    }

    @Override
    public void cancelPurchase(String platformPurchaseNo) throws AdapterException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(PARAM_TRADE_ID, require(platformPurchaseNo, "tradeID"));
        params.put(PARAM_CANCEL_REASON, CANCEL_REASON_OTHER);
        gateway.call(Ali1688Api.TRADE_CANCEL, params);
    }

    @Override
    public void payPurchase(String platformPurchaseNo) throws AdapterException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(PARAM_WITHHOLD_PARAM, json.tradeWithholdPreparePayParam(platformPurchaseNo));
        gateway.call(Ali1688Api.TRADE_PROTOCOL_PAY_PREPARE, params);
    }

    @Override
    public LogisticsTrace fetchLogistics(String platformPurchaseNo) throws AdapterException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put(PARAM_ORDER_ID, require(platformPurchaseNo, "orderId"));
        JsonNode body = gateway.call(Ali1688Api.LOGISTICS_TRACE_BUYER_VIEW, params);
        return json.toLogisticsTrace(platformPurchaseNo, body);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw AdapterException.nonRetryable("missing-required-field",
                    "1688 请求缺少必填参数 " + field + "（platformPurchaseNo）");
        }
        return value;
    }
}
