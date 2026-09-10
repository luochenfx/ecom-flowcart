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
     * <p><b>为什么 sourceSpecId 也必须落在行上</b>：{@code cargoParamList[]} 每项是
     * {@code {offerId, specId, quantity}}——{@code specId} 与 offer 同为<b>逐行</b>必填，且
     * {@code skuId}（规格组合内部 ID）≠ {@code specId}（下单键），不能用 {@link #sourceSkuId()}
     * 顶替。承载链：{@code OfferData.OfferSku.sourceSpecId}（采集面）→ master
     * {@code Sku.sourceSpecId} → 本行（#39 打通）。<b>值侧来源</b>（1688 响应里读哪个字段得到该值）
     * 仍由 #23 用真实响应 / 沙箱校准，不在本契约内。
     *
     * @param sourceSkuId   货源侧 SKU 标识（1688 skuId，规格组合的内部 ID）
     * @param sourceOfferId 货源侧<b>商品</b>标识（1688 offerId / productId，行级）：与 SPU master 的
     *                      {@code source_ref.external_id} 同源（采集面 {@code OfferData.externalId}）
     * @param sourceSpecId  货源侧<b>规格下单标识</b>（1688 specId，行级）：{@code cargoParamList[]}
     *                      每项的 {@code specId}，与 master {@code Sku.sourceSpecId} 同源
     * @param quantity      采购数量
     * @param unitPrice     采购单价
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PurchaseDraftItem(
            String sourceSkuId,
            String sourceOfferId,
            String sourceSpecId,
            Integer quantity,
            Money unitPrice) {
    }
}
