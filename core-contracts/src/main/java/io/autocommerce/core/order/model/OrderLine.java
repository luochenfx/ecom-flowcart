package io.autocommerce.core.order.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.autocommerce.core.catalog.model.Provenance;

import java.util.List;

/**
 * 订单行（schema: OrderLine，履约态载体）。业务事实（价格/规格）在快照行，本实体只承载履约关联。
 * snapshotLineRef 引用快照行；purchaseLineRefs 关联采购行（跨供应商拆单）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderLine(
        String orderLineId,
        String orderId,
        String platformLineId,
        String snapshotLineRef,
        String listingRef,
        List<PurchaseLineRef> purchaseLineRefs,
        Provenance provenance) {
}
