package io.autocommerce.order.testsupport;

import io.autocommerce.core.contract.AddressCapability;
import io.autocommerce.core.contract.Capability;
import io.autocommerce.core.contract.OrderSyncCapability;
import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.contract.RmaCapability;
import io.autocommerce.core.contract.ShipmentCapability;
import io.autocommerce.core.contract.dto.AddressRef;
import io.autocommerce.core.contract.dto.DecryptedAddress;
import io.autocommerce.core.contract.dto.OrderSyncPage;
import io.autocommerce.core.contract.dto.RmaStatusView;
import io.autocommerce.core.contract.dto.ShipmentNotification;
import io.autocommerce.core.contract.dto.SyncCursor;
import io.autocommerce.core.order.model.RmaStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * fixture 假<b>销售侧</b>平台 Adapter（#22 demo）：一个 provider 同时扮演销售平台侧的
 * {@link OrderSyncCapability}（拉取为真相）/ {@link ShipmentCapability}（发货回传）/ {@link RmaCapability}
 * （售后只读）/ {@link AddressCapability}（地址解密）。
 *
 * <p>为什么放 order 模块 test 作用域：fixture 只实现 core 能力接口，不含任何平台 SDK / 模型类
 * （禁环②）；也<b>不塞进 adapter-1688</b>——那是真实 Adapter、归 #23。随 order test-jar 暴露给
 * worker-runtime 端到端 demo 复用。
 *
 * <p>与真实 Adapter 的差别：这里以进程内 fixture 数据替代 WireMock HTTP 网关——fixture <b>本身就是</b>假
 * Adapter，HTTP 管线（签名 / 限流 / 错误映射）属真实 Adapter，随 #23 实测；本 demo 聚焦 order 域
 * 编排（同步 → 快照 → 拆采购单 → 发货回传 → RMA 读），不重复验证已由 adapter-1688 覆盖的 HTTP 层。
 *
 * <p>调用留痕（recordings）供测试断言：发货回传通知 / 地址解密请求 / RMA 查询。
 */
public final class FixtureSalesPlatformAdapter implements PlatformAdapterProvider, OrderSyncCapability,
        ShipmentCapability, RmaCapability, AddressCapability {

    /** 平台标识（销售侧 fixture；订单的 platform 字段与此一致）。 */
    public static final String PLATFORM = OrderFixtures.PLATFORM;

    private final boolean alwaysReturnPage;
    private final List<ShipmentNotification> notifications = new ArrayList<>();
    private final List<AddressRef> decryptRequests = new ArrayList<>();
    private final List<String> rmaQueries = new ArrayList<>();

    /** SPI 装配用：游标驱动（首访返回一页，之后返回空页 → pullAll 收敛）。 */
    public FixtureSalesPlatformAdapter() {
        this(false);
    }

    /** @param alwaysReturnPage true = 每次拉取都返回同一页（演示"重复事件不产生重复单"） */
    public FixtureSalesPlatformAdapter(boolean alwaysReturnPage) {
        this.alwaysReturnPage = alwaysReturnPage;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public Set<Class<? extends Capability>> capabilities() {
        return Set.of(OrderSyncCapability.class, ShipmentCapability.class, RmaCapability.class,
                AddressCapability.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Capability> T getCapability(Class<T> capabilityType) {
        if (capabilityType == OrderSyncCapability.class || capabilityType == ShipmentCapability.class
                || capabilityType == RmaCapability.class || capabilityType == AddressCapability.class) {
            return (T) this;
        }
        throw new IllegalArgumentException("fixture 销售侧 Adapter 未实现能力: " + capabilityType.getName());
    }

    // ---------------- OrderSyncCapability ----------------

    @Override
    public OrderSyncPage fetchOrders(SyncCursor cursor) {
        if (alwaysReturnPage) {
            return OrderFixtures.page();
        }
        boolean firstPage = cursor == null || cursor.cursor() == null;
        return firstPage ? OrderFixtures.page() : OrderFixtures.emptyPage();
    }

    // ---------------- ShipmentCapability ----------------

    @Override
    public void notifyShipment(ShipmentNotification notification) {
        notifications.add(notification);
    }

    // ---------------- RmaCapability ----------------

    @Override
    public RmaStatusView fetchRmaStatus(String platformRmaId) {
        rmaQueries.add(platformRmaId);
        // 售后已完结（REFUNDED 终态）→ 上层广播 rma.closed
        return new RmaStatusView(platformRmaId, RmaStatus.REFUNDED, "REFUND_SUCCESS");
    }

    // ---------------- AddressCapability ----------------

    @Override
    public DecryptedAddress decryptAddress(AddressRef ref) {
        decryptRequests.add(ref);
        return OrderFixtures.decryptedAddress();
    }

    // ---------------- recordings ----------------

    public List<ShipmentNotification> notifications() {
        return List.copyOf(notifications);
    }

    public List<AddressRef> decryptRequests() {
        return List.copyOf(decryptRequests);
    }

    public List<String> rmaQueries() {
        return List.copyOf(rmaQueries);
    }
}
