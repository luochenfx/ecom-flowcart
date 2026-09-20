package io.autocommerce.order.sync;

import com.fasterxml.jackson.databind.JsonNode;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.OrderSyncCapability;
import io.autocommerce.core.contract.dto.OrderSyncPage;
import io.autocommerce.core.contract.dto.SyncCursor;
import io.autocommerce.core.order.model.ChannelSyncState;
import io.autocommerce.core.order.model.Order;
import io.autocommerce.core.order.model.OrderModel;
import io.autocommerce.core.order.model.OrderRma;
import io.autocommerce.core.order.model.OrderSnapshot;
import io.autocommerce.order.model.OrderAggregate;
import io.autocommerce.order.rma.OrderRmaSource;
import io.autocommerce.order.snapshot.OrderAggregateAssembler;
import io.autocommerce.order.store.OrderStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 订单同步（specs/0003 §5 单写入路径）：<b>拉取 = 数据真相</b>。
 *
 * <p>唯一写入路径：读 {@link ChannelSyncState} 游标（落库、不进 Temporal）→ 经
 * {@link OrderSyncCapability} 增量拉取一页（以游标为准）→ 装配并<b>幂等落库</b> → 拉取成功后推进游标。
 * webhook（{@link OrderWebhookVerifier} 验签）只是"该渠道需要拉取"的唤醒信号，绝不直接建单。
 *
 * <p>两级幂等（specs/0003 §6）：
 * <ol>
 *   <li>入口 = 确定性 {@code workflowId = order-{platform}-{orderNo}}（由
 *       {@code OrderWorkflowLauncher} 承担，重复 start 命中既有 execution）；</li>
 *   <li>落库 = {@link OrderStore#saveOrderIfAbsent} 的 {@code (channel_id, platform_order_no)}
 *       唯一语义兜底——webhook 重推 / 游标重读被吸收，不产生重复单。</li>
 * </ol>
 *
 * <p>快照纪律：建单快照随首次落库固化；本类后续拉取<b>不覆写</b>既有订单聚合（命中既有即跳过），
 * 故平台改价 / SKU 变更永不影响历史订单（specs/0003 §3）。
 */
public final class OrderSyncService {

    /** 单次 pullAll 的页数安全上限（防 Adapter 游标不推进造成死循环）。 */
    private static final int MAX_PAGES = 100;

    private final String channelId;
    private final OrderSyncCapability orderSync;
    private final OrderStore store;
    private final OrderRmaSource rmaSource;
    private final Clock clock;
    private final OrderAggregateAssembler assembler = new OrderAggregateAssembler();

    public OrderSyncService(String channelId, OrderSyncCapability orderSync, OrderStore store,
                            OrderRmaSource rmaSource, Clock clock) {
        this.channelId = Objects.requireNonNull(channelId, "channelId 必填");
        this.orderSync = Objects.requireNonNull(orderSync, "OrderSyncCapability 必填");
        this.store = Objects.requireNonNull(store, "OrderStore 必填");
        this.rmaSource = Objects.requireNonNull(rmaSource, "OrderRmaSource 必填");
        this.clock = Objects.requireNonNull(clock, "Clock 必填");
    }

    /** 拉取一页并落库，返回本页结果；游标仅在全部落库成功后推进。 */
    public SyncResult pull() throws AdapterException {
        ChannelSyncState state = store.getChannelSyncState(channelId)
                .orElseGet(() -> new ChannelSyncState(channelId, null, null));
        SyncCursor cursor = new SyncCursor(channelId, state.cursor());
        OrderSyncPage page = orderSync.fetchOrders(cursor);

        List<String> created = new ArrayList<>();
        List<String> existing = new ArrayList<>();
        List<Order> orders = page.orders() == null ? List.of() : page.orders();
        for (Order order : orders) {
            OrderModel aggregate = assembler.assemble(order, snapshotFor(page, order));
            List<OrderRma> rmas = rmaSource.discover(order);
            if (rmas != null && !rmas.isEmpty()) {
                aggregate = OrderAggregate.of(aggregate).withRmas(rmas).toDocument();
            }
            OrderStore.SaveOutcome outcome = store.saveOrderIfAbsent(aggregate);
            (outcome.created() ? created : existing).add(order.orderId());
        }

        JsonNode next = page.nextCursor() == null ? state.cursor() : page.nextCursor().cursor();
        store.putChannelSyncState(new ChannelSyncState(channelId, next, clock.instant().toString()));
        return new SyncResult(orders.size(), List.copyOf(created), List.copyOf(existing));
    }

    /** 连续拉取直到游标耗尽（或达 {@link #MAX_PAGES} 上限），汇总各页结果。 */
    public SyncResult pullAll() throws AdapterException {
        List<String> created = new ArrayList<>();
        List<String> existing = new ArrayList<>();
        int fetched = 0;
        for (int i = 0; i < MAX_PAGES; i++) {
            SyncResult page = pull();
            fetched += page.fetched();
            created.addAll(page.createdOrderIds());
            existing.addAll(page.existingOrderIds());
            if (page.fetched() == 0) {
                break;
            }
        }
        return new SyncResult(fetched, List.copyOf(created), List.copyOf(existing));
    }

    private OrderSnapshot snapshotFor(OrderSyncPage page, Order order) {
        List<OrderSnapshot> snapshots = page.snapshots() == null ? List.of() : page.snapshots();
        return snapshots.stream()
                .filter(s -> s.snapshotId().equals(order.snapshotId()))
                .findFirst()
                .orElseGet(() -> {
                    if (snapshots.size() == 1) {
                        return snapshots.get(0);
                    }
                    throw new IllegalStateException("拉取页缺订单建单快照: " + order.orderId()
                            + " snapshotId=" + order.snapshotId());
                });
    }

    /**
     * 单页（或汇总）拉取结果。
     *
     * @param fetched           本页/累计拉取到的订单数
     * @param createdOrderIds   本次新建的 orderId（→ 由调用方 start order workflow）
     * @param existingOrderIds  命中既有、被幂等吸收的 orderId
     */
    public record SyncResult(int fetched, List<String> createdOrderIds, List<String> existingOrderIds) {
    }
}
