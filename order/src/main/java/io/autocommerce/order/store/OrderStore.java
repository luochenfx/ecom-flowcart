package io.autocommerce.order.store;

import io.autocommerce.core.order.model.ChannelSyncState;
import io.autocommerce.core.order.model.OrderModel;

import java.util.List;
import java.util.Optional;

/**
 * order 域文档库端口（#22 决议；沿用 #19 CatalogStore 范式：端口 + schema 校验 JSON 文档库）。
 *
 * <p><b>存储单元 = 订单聚合文档</b>：一个 {@link OrderModel} 恰含一条 Order 及其 OrderLine /
 * OrderSnapshot / PurchaseOrder / OrderRMA（channel_sync_states 由独立方法维护）。文档形态与
 * {@code schemas/order.schema.json} 一致，是"订单数据通过 schema 契约校验"的落地点。
 *
 * <p><b>幂等第二级（DB unique 兜底）在此端口语义化</b>：真实部署里 order 主表有
 * {@code (channel_id, platform_order_no)} unique constraint（specs/0003 §6）。v1 尚无真库，端口以
 * {@link #saveOrderIfAbsent} 的"同键已存在即返回既有、不新建"承载同一语义；真库
 * （Postgres/JPA）表结构属设计期未决区，届时以同一端口换实现，消费方（sync / workflow）不感知。
 *
 * <p><b>ChannelSyncState 游标</b>：每 channel 一行的增量拉取游标，纯位置指针、无状态机逻辑、
 * 不进 Temporal（specs/0003 §5）——它落本端口，不落 workflow history。
 */
public interface OrderStore {

    /**
     * 幂等写入单个订单聚合：若同 {@code (channel_id, platform_order_no)} 已存在，返回既有文档
     * 且不新建（吸收 webhook 重推 / 游标重读）。
     *
     * @return 落库结果（{@code created=false} 表示命中既有 order）
     */
    SaveOutcome saveOrderIfAbsent(OrderModel orderAggregate);

    /** 按 orderId 取回订单聚合；不存在返回 empty。 */
    Optional<OrderModel> getOrderById(String orderId);

    /** 按幂等键 {@code (channel_id, platform_order_no)} 查订单聚合（对账硬锚 / 幂等兜底）。 */
    Optional<OrderModel> findByChannelAndOrderNo(String channelId, String platformOrderNo);

    /** 列出全部订单聚合（demo / 看板 seed 用）。 */
    List<OrderModel> listOrders();

    /**
     * 覆盖式更新一个已存在的订单聚合（履约推进：解密地址 / 采购单 / 物流 / RMA）。
     * 订单聚合的不可变承诺只在快照层——快照由 {@link #saveOrderIfAbsent} 一次性写入后不再改动。
     *
     * @throws IllegalStateException 目标订单不存在（更新前须先落库）
     */
    OrderModel updateOrder(OrderModel orderAggregate);

    Optional<ChannelSyncState> getChannelSyncState(String channelId);

    void putChannelSyncState(ChannelSyncState state);

    /** 全量文档（契约门 / demo 人工检视）：所有订单 + 全部 channel 游标。 */
    OrderModel document();

    /**
     * 幂等写入结果。
     *
     * @param order   该订单的聚合文档（既有或新建）
     * @param created true = 本次新建；false = 命中既有（重复事件被吸收）
     */
    record SaveOutcome(OrderModel order, boolean created) {
    }
}
