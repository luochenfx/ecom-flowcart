package io.autocommerce.order.snapshot;

import io.autocommerce.core.catalog.model.Provenance;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.order.model.LineRef;
import io.autocommerce.core.order.model.LineSnapshot;
import io.autocommerce.core.order.model.Order;
import io.autocommerce.core.order.model.OrderLine;
import io.autocommerce.core.order.model.OrderModel;
import io.autocommerce.core.order.model.OrderSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * 建单落库装配：把 {@code OrderSyncCapability} 返回的 {@link Order} + {@link OrderSnapshot} 组装成
 * 单订单聚合文档，并生成 order 模块负责的<b>内部履约态</b> {@link OrderLine}（specs/0003 §2；
 * OrderSyncPage javadoc：OrderLine 等内部履约态由 order 模块落库时生成、不属 Adapter 契约）。
 *
 * <p>装配纪律：
 * <ul>
 *   <li>{@code Order.lines[i]} 与 {@code OrderSnapshot.line_snapshots[i]} <b>按位对齐</b>——
 *       Adapter 侧同一订单行在两侧同序产出；数量不等即 Adapter 契约违背（装配期 bug，显式失败）；
 *   <li>{@code OrderLine.platform_line_id} 是行级幂等锚 {@code (platform_order_no, platform_line_id)}：
 *       OrderSyncCapability 契约未单独承载平台行号，v1 以确定性行序号派生
 *       （{@code {platform_order_no}-{i+1}}）；真实平台行号随 #23 Adapter 全能力收口回填（若需
 *       独立承载则按 ADR-0007 走契约加法）；
 *   <li>{@code OrderLine.snapshot_line_ref} → 对应快照行 id；{@code listing_ref} → 快照行 my_ref
 *       （若可回溯到自有 Listing）；
 *   <li>快照只读：本类不修改 {@link OrderSnapshot} 任何字段。
 * </ul>
 */
public final class OrderAggregateAssembler {

    private static final String SCHEMA_VERSION = "0.1.0";

    public OrderModel assemble(Order order, OrderSnapshot snapshot) {
        List<OrderLine> lines = buildLines(order, snapshot);
        return new OrderModel(SCHEMA_VERSION, List.of(order), lines, List.of(snapshot),
                List.of(), List.of(), List.of());
    }

    private static List<OrderLine> buildLines(Order order, OrderSnapshot snapshot) {
        List<LineRef> refs = order.lines() == null ? List.of() : order.lines();
        List<LineSnapshot> snapshots = snapshot.lineSnapshots() == null ? List.of() : snapshot.lineSnapshots();
        if (refs.size() != snapshots.size()) {
            throw new IllegalStateException("订单行数与快照行数不等（Adapter 契约违背）: order="
                    + order.orderId() + " lines=" + refs.size() + " snapshotLines=" + snapshots.size());
        }
        String capturedAt = snapshot.capturedAt();
        Provenance provenance = new Provenance(ProvenanceStep.CAPTURE, null, order.orderId(),
                capturedAt, capturedAt);
        List<OrderLine> lines = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            LineSnapshot lineSnapshot = snapshots.get(i);
            String platformLineId = order.platformOrderNo() + "-" + (i + 1);
            lines.add(new OrderLine(refs.get(i).orderLineId(), order.orderId(), platformLineId,
                    lineSnapshot.snapshotLineId(), lineSnapshot.myRef(), List.of(), provenance));
        }
        return List.copyOf(lines);
    }
}
