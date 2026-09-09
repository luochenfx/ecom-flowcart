package io.autocommerce.core.contract;

import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.OrderSyncPage;
import io.autocommerce.core.contract.dto.SyncCursor;

/**
 * 订单同步能力（specs/0005 §2，消费方 = order workflow #8）。拉取 = 数据真相，
 * webhook 只作唤醒信号；游标由 core 传 {@link SyncCursor}（与 ChannelSyncState 同源）。
 */
public interface OrderSyncCapability extends Capability {

    /** 增量拉取一页订单（Adapter 做平台 → 标准 Order + OrderSnapshot 的结构转换） */
    OrderSyncPage fetchOrders(SyncCursor cursor) throws AdapterException;
}
