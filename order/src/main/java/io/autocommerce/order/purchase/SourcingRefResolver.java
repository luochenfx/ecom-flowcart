package io.autocommerce.order.purchase;

import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.order.model.LineSnapshot;
import io.autocommerce.core.order.model.SupplierRef;

import java.util.Optional;

/**
 * 货源解析端口（order 模块本地 seam）：把一条销售订单行映射到货源（1688）供应商 + offer/spec/sku
 * 坐标 + 成本价，供拆采购单使用。
 *
 * <p><b>为什么是端口</b>：真实映射依赖 catalog master（SPU → source_ref 货源.offer、SKU →
 * source_sku_id / source_spec_id）与供应商归属，属跨域读；v1 以端口占位，装配点接 catalog 时换实现。
 * demo / 测试用 fixture 实现（两行分属两供应商 → 演示跨供应商拆单）。
 *
 * <p>{@link SourcingRef#unitPrice()} 用 catalog 域的 decimal-string {@link Money}（货源侧成本价口径），
 * 与 order 域 number 型 Money 保持独立（见两域各自 schema）。
 */
public interface SourcingRefResolver {

    /** 解析一条快照行的货源；无映射返回 empty（调用方决定是失败还是跳过）。 */
    Optional<SourcingRef> resolve(LineSnapshot lineSnapshot);

    /** 无货源来源的默认实现（订单不进入采购时使用）。 */
    static SourcingRefResolver none() {
        return lineSnapshot -> Optional.empty();
    }

    /**
     * 货源坐标。
     *
     * @param supplier       1688 供应商（采购单仅限同供应商）
     * @param sourceOfferId  货源侧商品标识（1688 offerId / productId）：cargoParamList[].offerId
     * @param sourceSpecId   货源侧规格下单标识（1688 specId）：cargoParamList[].specId
     * @param sourceSkuId    货源侧 SKU 标识（1688 skuId，规格组合内部 id）
     * @param unitPrice      采购单价（货源侧成本价）
     */
    record SourcingRef(SupplierRef supplier, String sourceOfferId, String sourceSpecId,
                       String sourceSkuId, Money unitPrice) {
    }
}
