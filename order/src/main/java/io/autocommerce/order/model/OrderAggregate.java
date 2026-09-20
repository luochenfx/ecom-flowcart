package io.autocommerce.order.model;

import io.autocommerce.core.order.model.FulfillmentStatus;
import io.autocommerce.core.order.model.Order;
import io.autocommerce.core.order.model.OrderLine;
import io.autocommerce.core.order.model.OrderModel;
import io.autocommerce.core.order.model.OrderRma;
import io.autocommerce.core.order.model.OrderSnapshot;
import io.autocommerce.core.order.model.PurchaseLine;
import io.autocommerce.core.order.model.PurchaseLineRef;
import io.autocommerce.core.order.model.PurchaseOrder;
import io.autocommerce.core.order.model.RmaRef;
import io.autocommerce.core.order.model.ShippingAddress;
import io.autocommerce.core.order.model.Timestamps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单聚合（订单模块内部工作视图，非 core 契约类型）。
 *
 * <p>把一份单订单 {@link OrderModel} 文档（Order + OrderLine / OrderSnapshot / PurchaseOrder /
 * OrderRMA）包成可派生的不可变视图，集中处理"record 重建"的样板：履约轴推进、地址解密态变化、
 * 采购单并入（同时回填订单行的 {@code purchase_line_refs}）、RMA 并入（同时回填订单的
 * {@code rmas} 引用）。
 *
 * <p>不改变快照：{@link #snapshot()} 只读，快照在建单时一次写入后永不 UPDATE
 * （specs/0003 §3；改价/SKU 变更不影响历史订单）。
 */
public final class OrderAggregate {

    private final OrderModel doc;

    private OrderAggregate(OrderModel doc) {
        this.doc = doc;
    }

    /** 从单订单聚合文档构造；orders() 必须恰 1 条。 */
    public static OrderAggregate of(OrderModel doc) {
        if (doc == null || doc.orders() == null || doc.orders().size() != 1) {
            throw new IllegalArgumentException("订单聚合文档 orders() 必须恰 1 条");
        }
        return new OrderAggregate(doc);
    }

    public Order order() {
        return doc.orders().get(0);
    }

    /** 建单快照（业务事实不可变源）；按 order.snapshotId() 定位，缺失时取首条。 */
    public OrderSnapshot snapshot() {
        if (doc.orderSnapshots() == null || doc.orderSnapshots().isEmpty()) {
            throw new IllegalStateException("订单聚合缺建单快照: " + order().orderId());
        }
        String snapshotId = order().snapshotId();
        return doc.orderSnapshots().stream()
                .filter(s -> s.snapshotId().equals(snapshotId))
                .findFirst()
                .orElse(doc.orderSnapshots().get(0));
    }

    public List<OrderLine> lines() {
        return nullSafe(doc.orderLines());
    }

    public List<PurchaseOrder> purchaseOrders() {
        return nullSafe(doc.purchaseOrders());
    }

    public List<OrderRma> rmas() {
        return nullSafe(doc.rmas());
    }

    public OrderModel toDocument() {
        return doc;
    }

    // ---------------- 派生 ----------------

    /** 推进销售履约轴（canonical，由 workflow 派生后写投影；specs/0003 §4）。 */
    public OrderAggregate withFulfillmentStatus(FulfillmentStatus status) {
        Order o = order();
        Order updated = new Order(o.orderId(), o.channelId(), o.platform(), o.platformOrderNo(),
                o.platformStatus(), o.platformStatusTime(), o.platformRaw(), status, o.snapshotId(),
                o.shippingAddress(), o.buyer(), o.lines(), o.rmas(), o.timestamps(), o.provenance());
        return new OrderAggregate(replaceOrder(updated));
    }

    /** 更新收货地址（MASKED → DECRYPTED 的加密负载落库；履约可后补的操作数据）。 */
    public OrderAggregate withShippingAddress(ShippingAddress address) {
        Order o = order();
        Order updated = new Order(o.orderId(), o.channelId(), o.platform(), o.platformOrderNo(),
                o.platformStatus(), o.platformStatusTime(), o.platformRaw(), o.fulfillmentStatus(),
                o.snapshotId(), address, o.buyer(), o.lines(), o.rmas(), o.timestamps(), o.provenance());
        return new OrderAggregate(replaceOrder(updated));
    }

    /** 更新订单时间戳。 */
    public OrderAggregate withTimestamps(Timestamps timestamps) {
        Order o = order();
        Order updated = new Order(o.orderId(), o.channelId(), o.platform(), o.platformOrderNo(),
                o.platformStatus(), o.platformStatusTime(), o.platformRaw(), o.fulfillmentStatus(),
                o.snapshotId(), o.shippingAddress(), o.buyer(), o.lines(), o.rmas(), timestamps,
                o.provenance());
        return new OrderAggregate(replaceOrder(updated));
    }

    /**
     * 并入采购单（按 purchaseOrderId 覆盖），并回填订单行的 {@code purchase_line_refs}
     * （一销售行可拆多采购行：跨供应商拆单）。
     */
    public OrderAggregate withPurchaseOrders(List<PurchaseOrder> additions) {
        List<PurchaseOrder> merged = replaceById(purchaseOrders(), nullSafe(additions),
                PurchaseOrder::purchaseOrderId);

        // 回填订单行的采购行引用（确定性重建，避免重复追加）
        Map<String, List<PurchaseLineRef>> refs = new LinkedHashMap<>();
        for (PurchaseOrder po : merged) {
            for (PurchaseLine pl : nullSafe(po.lines())) {
                refs.computeIfAbsent(pl.orderLineRef(), k -> new ArrayList<>())
                        .add(new PurchaseLineRef(po.purchaseOrderId(), pl.purchaseLineId()));
            }
        }
        List<OrderLine> newLines = new ArrayList<>();
        for (OrderLine l : lines()) {
            List<PurchaseLineRef> lineRefs = List.copyOf(refs.getOrDefault(l.orderLineId(), List.of()));
            newLines.add(new OrderLine(l.orderLineId(), l.orderId(), l.platformLineId(),
                    l.snapshotLineRef(), l.listingRef(), lineRefs, l.provenance()));
        }
        return new OrderAggregate(new OrderModel(doc.schemaVersion(), doc.orders(), newLines,
                doc.orderSnapshots(), merged, doc.rmas(), doc.channelSyncStates()));
    }

    /** 并入 / 更新 RMA（按 rmaId 覆盖），并重建 Order.rmas 引用。 */
    public OrderAggregate withRmas(List<OrderRma> additions) {
        List<OrderRma> merged = replaceById(rmas(), nullSafe(additions), OrderRma::rmaId);
        List<RmaRef> refs = merged.stream().map(r -> new RmaRef(r.rmaId())).toList();
        Order o = order();
        Order updated = new Order(o.orderId(), o.channelId(), o.platform(), o.platformOrderNo(),
                o.platformStatus(), o.platformStatusTime(), o.platformRaw(), o.fulfillmentStatus(),
                o.snapshotId(), o.shippingAddress(), o.buyer(), o.lines(), refs, o.timestamps(),
                o.provenance());
        return new OrderAggregate(new OrderModel(doc.schemaVersion(), List.of(updated), doc.orderLines(),
                doc.orderSnapshots(), doc.purchaseOrders(), merged, doc.channelSyncStates()));
    }

    private OrderModel replaceOrder(Order order) {
        return new OrderModel(doc.schemaVersion(), List.of(order), doc.orderLines(), doc.orderSnapshots(),
                doc.purchaseOrders(), doc.rmas(), doc.channelSyncStates());
    }

    private static <T> List<T> replaceById(List<T> base, List<T> additions,
                                           java.util.function.Function<T, String> id) {
        Map<String, T> byId = new LinkedHashMap<>();
        for (T e : base) {
            byId.put(id.apply(e), e);
        }
        for (T e : additions) {
            byId.put(id.apply(e), e);
        }
        return List.copyOf(byId.values());
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
