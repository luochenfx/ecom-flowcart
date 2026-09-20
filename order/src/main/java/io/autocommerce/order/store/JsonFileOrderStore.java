package io.autocommerce.order.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.autocommerce.core.order.model.ChannelSyncState;
import io.autocommerce.core.order.model.Order;
import io.autocommerce.core.order.model.OrderLine;
import io.autocommerce.core.order.model.OrderModel;
import io.autocommerce.core.order.model.OrderRma;
import io.autocommerce.core.order.model.OrderSnapshot;
import io.autocommerce.core.order.model.PurchaseOrder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JsonFileOrderStore —— v1 {@link OrderStore} 实现（schema 校验 JSON 文档库，全库单文档）。
 *
 * <p>整个订单库 = 一份 {@code {root}/orders.json}（{@link OrderModel} 文档：snake_case 序列化、
 * null 省略、缩进输出，与 order.schema.json 同形）。单文档形态便于人工检视 / demo「落库可见」，
 * 也让"订单聚合 + 渠道游标"在一处原子落盘；真库实现后续同端口替换（见 {@link OrderStore} javadoc）。
 *
 * <p>写路径不做运行时 schema 校验（校验器仅 test scope，Testing §1 由契约测试承担门）；调用方
 * 负责保证写入文档通过 order schema。
 */
public final class JsonFileOrderStore implements OrderStore {

    /**
     * 与 order.schema.json 的 {@code schema_version} const 一致。
     */
    static final String SCHEMA_VERSION = "0.1.0";

    private final Path file;
    private final ObjectMapper mapper;

    public JsonFileOrderStore(Path root) {
        this.file = root.resolve("orders.json");
        this.mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public SaveOutcome saveOrderIfAbsent(OrderModel orderAggregate) {
        Order incoming = singleOrder(orderAggregate);
        OrderModel doc = read();
        Optional<OrderModel> existing = findByKey(doc, incoming.channelId(), incoming.platformOrderNo());
        if (existing.isPresent()) {
            return new SaveOutcome(existing.get(), false);
        }
        write(merge(doc, orderAggregate));
        return new SaveOutcome(orderAggregate, true);
    }

    @Override
    public Optional<OrderModel> getOrderById(String orderId) {
        return extract(read(), orderId);
    }

    @Override
    public Optional<OrderModel> findByChannelAndOrderNo(String channelId, String platformOrderNo) {
        return findByKey(read(), channelId, platformOrderNo);
    }

    @Override
    public List<OrderModel> listOrders() {
        OrderModel doc = read();
        return doc.orders().stream().map(o -> extract(doc, o.orderId()).orElseThrow()).toList();
    }

    @Override
    public OrderModel updateOrder(OrderModel orderAggregate) {
        Order incoming = singleOrder(orderAggregate);
        OrderModel doc = read();
        Optional<OrderModel> existing = extract(doc, incoming.orderId());
        if (existing.isEmpty()) {
            throw new IllegalStateException("updateOrder 目标订单不存在，需先 saveOrderIfAbsent: "
                    + incoming.orderId());
        }
        assertSnapshotsUnchanged(existing.get(), orderAggregate);
        write(replace(doc, orderAggregate));
        return orderAggregate;
    }

    @Override
    public Optional<ChannelSyncState> getChannelSyncState(String channelId) {
        return read().channelSyncStates().stream()
                .filter(s -> s.channelId().equals(channelId))
                .findFirst();
    }

    @Override
    public void putChannelSyncState(ChannelSyncState state) {
        OrderModel doc = read();
        List<ChannelSyncState> states = new ArrayList<>(doc.channelSyncStates().stream()
                .filter(s -> !s.channelId().equals(state.channelId()))
                .toList());
        states.add(state);
        write(new OrderModel(doc.schemaVersion(), doc.orders(), doc.orderLines(), doc.orderSnapshots(),
                doc.purchaseOrders(), doc.rmas(), List.copyOf(states)));
    }

    @Override
    public OrderModel document() {
        return read();
    }

    // ---------------- 内部：读写 ----------------

    private OrderModel read() {
        if (!Files.isRegularFile(file)) {
            return empty();
        }
        try {
            return mapper.treeToValue(mapper.readTree(file.toFile()), OrderModel.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 order 文档失败: " + file, e);
        }
    }

    private void write(OrderModel doc) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writer().writeValue(file.toFile(), doc);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 order 文档失败: " + file, e);
        }
    }

    private static OrderModel empty() {
        return new OrderModel(SCHEMA_VERSION, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    // ---------------- 内部：聚合抽取 / 合并 ----------------

    /**
     * 从全量文档抽出单订单聚合（只含该 orderId 的实体 + 空游标）。
     */
    static Optional<OrderModel> extract(OrderModel doc, String orderId) {
        Optional<Order> order = doc.orders().stream()
                .filter(o -> o.orderId().equals(orderId))
                .findFirst();
        return order.map(value -> new OrderModel(SCHEMA_VERSION, List.of(value),
                filterByOrderId(doc.orderLines(), OrderLine::orderId, orderId),
                filterByOrderId(doc.orderSnapshots(), OrderSnapshot::orderId, orderId),
                filterByOrderId(doc.purchaseOrders(), PurchaseOrder::orderId, orderId),
                filterByOrderId(doc.rmas(), OrderRma::orderId, orderId),
                List.of()));
    }

    private static Optional<OrderModel> findByKey(OrderModel doc, String channelId, String platformOrderNo) {
        return doc.orders().stream()
                .filter(o -> o.channelId().equals(channelId)
                        && o.platformOrderNo().equals(platformOrderNo))
                .findFirst()
                .flatMap(o -> extract(doc, o.orderId()));
    }

    /**
     * 把单订单聚合并入全量文档（保持既有订单与游标）。
     */
    private static OrderModel merge(OrderModel doc, OrderModel aggregate) {
        return new OrderModel(SCHEMA_VERSION,
                concat(doc.orders(), aggregate.orders()),
                concat(doc.orderLines(), aggregate.orderLines()),
                concat(doc.orderSnapshots(), aggregate.orderSnapshots()),
                concat(doc.purchaseOrders(), aggregate.purchaseOrders()),
                concat(doc.rmas(), aggregate.rmas()),
                doc.channelSyncStates());
    }

    /**
     * 用聚合替换全量文档中同 orderId 的部分（先剔除旧实体，再并入新实体）。
     */
    private static OrderModel replace(OrderModel doc, OrderModel aggregate) {
        String orderId = singleOrder(aggregate).orderId();
        return new OrderModel(SCHEMA_VERSION,
                concat(removeByOrderId(doc.orders(), Order::orderId, orderId), aggregate.orders()),
                concat(removeByOrderId(doc.orderLines(), OrderLine::orderId, orderId), aggregate.orderLines()),
                concat(removeByOrderId(doc.orderSnapshots(), OrderSnapshot::orderId, orderId),
                        aggregate.orderSnapshots()),
                concat(removeByOrderId(doc.purchaseOrders(), PurchaseOrder::orderId, orderId),
                        aggregate.purchaseOrders()),
                concat(removeByOrderId(doc.rmas(), OrderRma::orderId, orderId), aggregate.rmas()),
                doc.channelSyncStates());
    }

    private static Order singleOrder(OrderModel aggregate) {
        if (aggregate.orders() == null || aggregate.orders().size() != 1) {
            throw new IllegalArgumentException(
                    "OrderStore 存储单元 = 单个订单聚合文档，orders() 必须恰 1 条");
        }
        return aggregate.orders().getFirst();
    }

    /**
     * specs/0003 §3 铁律的机械守卫：{@link OrderSnapshot} 建单后不可 UPDATE。覆盖式更新只推进聚合的
     * 操作态（地址 / 采购单 / 物流 / RMA / 履约轴），既有快照必须原样前移；一旦入参快照集合与既有不一致
     * （新增 / 覆盖 / 删除）即拒绝覆写——不靠调用方约定，落库层拦下。
     */
    private static void assertSnapshotsUnchanged(OrderModel existing, OrderModel incoming) {
        if (!nullSafe(existing.orderSnapshots()).equals(nullSafe(incoming.orderSnapshots()))) {
            throw new IllegalStateException("OrderSnapshot 不可变（specs/0003 §3）：updateOrder 不得变更既有"
                    + "快照（新增 / 覆盖 / 删除均禁止）, orderId=" + singleOrder(incoming).orderId());
        }
    }

    private static <T> List<T> filterByOrderId(List<T> source, java.util.function.Function<T, String> id, String orderId) {
        return nullSafe(source).stream().filter(e -> orderId.equals(id.apply(e))).toList();
    }

    private static <T> List<T> removeByOrderId(List<T> source, java.util.function.Function<T, String> id, String orderId) {
        return nullSafe(source).stream().filter(e -> !orderId.equals(id.apply(e))).toList();
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        return java.util.stream.Stream.concat(nullSafe(a).stream(), nullSafe(b).stream()).toList();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
