package io.autocommerce.core.contract.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.order.model.SupplierRef;

import java.util.List;

/**
 * 1688 采购草稿（PurchaseCapability.createPurchase 入参）。由 order workflow 组装
 * （销售订单行 → 同供应商采购行 + 明文收货地址），下采购单前地址已解密。
 *
 * <p>字段齐备性 = 1688 {@code alibaba.trade.fastCreateOrder} 请求体可构造：
 * {@code cargoParamList[]} 每项必需 {@code offerId}（商品）+ {@code specId}（规格下单标识）+
 * {@code quantity}，{@code addressParam} ← {@link DecryptedAddress}。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PurchaseDraft(
        SupplierRef supplierRef,
        List<PurchaseDraftItem> items,
        DecryptedAddress recipient) {

    /**
     * 采购草稿行。
     *
     * <p><b>为什么 sourceOfferId 必须落在行上</b>：{@link SupplierRef#supplierId()} 是<b>卖家</b>维度，
     * 一个卖家可有多个 offer；1688 只禁止<b>跨供应商</b>合单、允许<b>同供应商多 offer</b>合单 → 订单级
     * （供应商）放不下"哪个 offer"，行级才够。缺它则请求体根本构造不出来（缺必填项，不是值不准）。
     *
     * <p><b>遗留（#23 消费时一并定）</b>：{@code cargoParamList[]} 每项还要 {@code specId}，其标准模型
     * 承载 = {@code OfferData.OfferSku.sourceSpecId}；该值从 master 到本行（{@code Sku} master / 本
     * record 是否加字段）的承载尚未落地——待 #23 用真实响应校准 specId 语义后决定载体，避免先拍脑袋加
     * 一个可能错的字段。
     *
     * @param sourceSkuId   货源侧 SKU 标识（1688 skuId，规格组合的内部 ID）
     * @param sourceOfferId 货源侧<b>商品</b>标识（1688 offerId / productId，行级）：与 SPU master 的
     *                      {@code source_ref.external_id} 同源（采集面 {@code OfferData.externalId}）
     * @param quantity      采购数量
     * @param unitPrice     采购单价
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PurchaseDraftItem(
            String sourceSkuId,
            String sourceOfferId,
            Integer quantity,
            Money unitPrice) {
    }
}
