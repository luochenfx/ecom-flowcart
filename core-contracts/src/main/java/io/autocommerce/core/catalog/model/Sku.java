package io.autocommerce.core.catalog.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;

/**
 * SKU（master 规格变体）—— schema: Sku。spuId/specs/costPrice/provenance 必填；
 * barcode 可空、images 可选变体图。
 *
 * <p><b>货源侧标识符两者不可互换</b>（CONTEXT「外部标识符前缀」）：{@code sourceSkuId} = 1688 skuMap
 * 原始 skuId，标识"哪个规格组合"（内部 ID）；{@code sourceSpecId} = 1688 下单键 {@code specId}
 * （{@code cargoParamList[]} 每项必填，见 {@code PurchaseDraft.PurchaseDraftItem}），下单时 1688 要的
 * 规格标识。两者均可空（老数据、或采集侧尚未填充）。
 *
 * <p>值侧来源（1688 响应里读哪个字段得到 {@code sourceSpecId}）由 #23 用真实响应 / 沙箱校准后落采集侧
 * 填充；本模型只定字段与载体（#39）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Sku(
        String skuId,
        String spuId,
        List<SpecValue> specs,
        Money costPrice,
        String barcode,
        List<MediaRef> images,
        String sourceSkuId,
        String sourceSpecId,
        Provenance provenance) {
}
