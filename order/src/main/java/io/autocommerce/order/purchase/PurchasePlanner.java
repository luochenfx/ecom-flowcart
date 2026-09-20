package io.autocommerce.order.purchase;

import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.order.model.LineSnapshot;
import io.autocommerce.core.order.model.OrderLine;
import io.autocommerce.core.order.model.SupplierRef;
import io.autocommerce.order.model.OrderAggregate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 采购拆单（specs/0003 §1/§2：Order → 1:N PurchaseOrder；1688 {@code fastCreateOrder} 仅限<b>同供应商
 * 合单</b>，<b>跨供应商必须拆单</b>）。
 *
 * <p>算法：逐销售订单行 → 定位其快照行（业务事实源）→ 经 {@link SourcingRefResolver} 解析货源 →
 * 按 {@code supplier.supplierId()} 分组 → 每组一张采购单。同供应商多 offer 自然合单（行一起进同组），
 * 跨供应商自动拆成多张。分组顺序 = 首次出现顺序（确定性，便于断言与对账）。
 *
 * <p>缺货源映射或快照行缺失 = 数据/装配错误（不是业务降级），显式失败——不静默产出漏采购的订单。
 */
public final class PurchasePlanner {

    private final SourcingRefResolver resolver;

    public PurchasePlanner(SourcingRefResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "SourcingRefResolver 必填");
    }

    public List<PurchasePlan> plan(OrderAggregate order) {
        Map<String, List<PlannedLine>> bySupplier = new LinkedHashMap<>();
        Map<String, SupplierRef> suppliers = new LinkedHashMap<>();
        for (OrderLine line : order.lines()) {
            LineSnapshot snapshotLine = snapshotLine(order, line);
            SourcingRefResolver.SourcingRef sourcing = resolver.resolve(snapshotLine)
                    .orElseThrow(() -> new IllegalStateException(
                            "缺货源映射，无法拆采购单: orderLine=" + line.orderLineId()
                                    + " myRef=" + snapshotLine.myRef()));
            int quantity = snapshotLine.quantity() == null ? 0 : snapshotLine.quantity();
            if (quantity <= 0) {
                throw new IllegalStateException("订单行数量非法: orderLine=" + line.orderLineId()
                        + " quantity=" + snapshotLine.quantity());
            }
            SupplierRef supplier = sourcing.supplier();
            suppliers.putIfAbsent(supplier.supplierId(), supplier);
            bySupplier.computeIfAbsent(supplier.supplierId(), k -> new ArrayList<>())
                    .add(new PlannedLine(line.orderLineId(), sourcing.sourceOfferId(),
                            sourcing.sourceSpecId(), sourcing.sourceSkuId(), quantity,
                            sourcing.unitPrice()));
        }
        List<PurchasePlan> plans = new ArrayList<>();
        bySupplier.forEach((supplierId, lines) ->
                plans.add(new PurchasePlan(suppliers.get(supplierId), List.copyOf(lines))));
        return List.copyOf(plans);
    }

    private static LineSnapshot snapshotLine(OrderAggregate order, OrderLine line) {
        return order.snapshot().lineSnapshots().stream()
                .filter(s -> s.snapshotLineId().equals(line.snapshotLineRef()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "订单行的快照行缺失: orderLine=" + line.orderLineId()
                                + " snapshotLineRef=" + line.snapshotLineRef()));
    }

    /**
     * 一张采购单的规划（同一供应商）。
     */
    public record PurchasePlan(SupplierRef supplier, List<PlannedLine> lines) {
    }

    /**
     * 采购单内的一行（回指销售订单行 + 货源坐标 + 数量/成本价）。
     */
    public record PlannedLine(String orderLineId, String sourceOfferId, String sourceSpecId,
                              String sourceSkuId, int quantity, Money unitPrice) {
    }
}
