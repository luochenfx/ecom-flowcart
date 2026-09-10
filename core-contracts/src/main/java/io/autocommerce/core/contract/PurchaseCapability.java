package io.autocommerce.core.contract;

import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.LogisticsTrace;
import io.autocommerce.core.contract.dto.PurchaseDraft;
import io.autocommerce.core.contract.dto.PurchaseResult;

/**
 * 货源采购能力（specs/0005 §2，逐项映射见 §9）。1688 侧是<b>四段独立调用</b>：
 * 下单（{@code alibaba.trade.fastCreateOrder}，{@code flow=saleproxy} 一件代发）→ 支付
 * （免密代扣 {@code alibaba.trade.pay.protocolPay.preparePay} / 收银台 {@code alibaba.alipay.url.get}）
 * → 撤销（{@code alibaba.trade.cancel}，<b>仅未付款可撤</b>；已付款走售后退款）→ 物流
 * （{@code alibaba.logistics.trace}）。
 */
public interface PurchaseCapability extends Capability {

    PurchaseResult createPurchase(PurchaseDraft draft) throws AdapterException;

    /**
     * 撤销 <b>未付款</b> 采购单。已付款订单 1688 不允许撤单，须走售后退款（不在本路径）。
     *
     * @param platformPurchaseNo 1688 侧采购单号（记录所属平台的原始号，见 CONTEXT「外部标识符前缀」）
     */
    void cancelPurchase(String platformPurchaseNo) throws AdapterException;

    /**
     * 支付采购单（免密代扣；无代扣协议时平台返回收银台链接）。
     *
     * <p>返回类型暂定 void：1688 支付/收银台接口可能返回支付链接或支付态，是否需要在契约上回传
     * 待 #23 实测后定（若需要，届时新增返回类型 = 契约扩展，按 ADR-0007 走加法）。
     *
     * @param platformPurchaseNo 1688 侧采购单号
     */
    void payPurchase(String platformPurchaseNo) throws AdapterException;

    LogisticsTrace fetchLogistics(String platformPurchaseNo) throws AdapterException;
}
