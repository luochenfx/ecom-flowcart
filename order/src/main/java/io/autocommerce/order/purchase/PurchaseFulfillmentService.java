package io.autocommerce.order.purchase;

import io.autocommerce.core.contract.AddressCapability;
import io.autocommerce.core.contract.PurchaseCapability;
import io.autocommerce.core.contract.ShipmentCapability;
import io.autocommerce.core.contract.dto.AddressRef;
import io.autocommerce.core.contract.dto.DecryptedAddress;
import io.autocommerce.core.contract.dto.LogisticsTrace;
import io.autocommerce.core.contract.dto.PurchaseDraft;
import io.autocommerce.core.contract.dto.PurchaseDraft.PurchaseDraftItem;
import io.autocommerce.core.contract.dto.PurchaseResult;
import io.autocommerce.core.contract.dto.ShipmentNotification;
import io.autocommerce.core.catalog.model.Provenance;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.order.model.FulfillmentStatus;
import io.autocommerce.core.order.model.Money;
import io.autocommerce.core.order.model.Order;
import io.autocommerce.core.order.model.PurchaseLine;
import io.autocommerce.core.order.model.PurchaseOrder;
import io.autocommerce.core.order.model.PurchaseStatus;
import io.autocommerce.core.order.model.ShippingAddress;
import io.autocommerce.core.order.model.ShippingAddressState;
import io.autocommerce.core.order.model.Timestamps;
import io.autocommerce.core.order.model.Tracking;
import io.autocommerce.order.address.AddressCipher;
import io.autocommerce.order.model.OrderAggregate;
import io.autocommerce.order.status.FulfillmentDeriver;
import io.autocommerce.order.store.OrderStore;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 采购履约服务：<b>拆单 → 下采购单（先解密地址）→ 发货回传</b>（specs/0003 §1/§7；ADR-0005）。
 *
 * <p>两种粒度：
 * <ul>
 *   <li><b>单采购单（按供应商）</b>——{@link #planSupplierIds} / {@link #placePurchase} /
 *       {@link #returnShipment}：供<b>采购单自有 workflow</b> 逐张驱动（一张销售单拆 N 张采购单 =
 *       N 个 purchase workflow，销售侧 SHIPPED 由采购集合派生，无双写）；</li>
 *   <li><b>整单</b>——{@link #placePurchases} / {@link #returnShipments}：串起全部供应商，供
 *       order 模块级 demo / 单测（也等价于 N 个采购 workflow 的聚合结果）。</li>
 * </ul>
 *
 * <p>时序纪律（"事件总线只承载已落库事实"，specs/0016 §0.3）：下采购单前才解密地址
 * （{@link #ensureAddressDecrypted}，明文 AES 落库）；发货回传在 {@link #returnShipment} 内落库，
 * 事件由 worker 层在落库后广播。
 *
 * <p>地址解密时机是本服务唯一的平台明文触点：仅在确有采购计划时触发（无采购不解密）。
 * 已 DECRYPTED 则复用密文本地解密，不重复调用平台解密 API。
 *
 * <p>幂等：{@link #placePurchase} 该供应商已有采购单即返回（不重复下单——花钱的外部副作用）；
 * {@link #returnShipment} 对已 SHIPPED/COMPLETED/CANCELLED 的采购单跳过。
 */
public final class PurchaseFulfillmentService {

    private static final Set<PurchaseStatus> TERMINAL_PURCHASE = Set.of(
            PurchaseStatus.SHIPPED, PurchaseStatus.COMPLETED, PurchaseStatus.CANCELLED);

    private final OrderStore store;
    private final PurchaseCapability purchase;
    private final ShipmentCapability shipment;
    private final AddressCapability address;
    private final AddressCipher cipher;
    private final PurchasePlanner planner;
    private final Clock clock;

    public PurchaseFulfillmentService(OrderStore store, PurchaseCapability purchase,
                                      ShipmentCapability shipment, AddressCapability address,
                                      AddressCipher cipher, PurchasePlanner planner, Clock clock) {
        this.store = Objects.requireNonNull(store, "OrderStore 必填");
        this.purchase = Objects.requireNonNull(purchase, "PurchaseCapability 必填");
        this.shipment = Objects.requireNonNull(shipment, "ShipmentCapability 必填");
        this.address = Objects.requireNonNull(address, "AddressCapability 必填");
        this.cipher = Objects.requireNonNull(cipher, "AddressCipher 必填");
        this.planner = Objects.requireNonNull(planner, "PurchasePlanner 必填");
        this.clock = Objects.requireNonNull(clock, "Clock 必填");
    }

    // ---------------- 单采购单（采购 workflow 粒度） ----------------

    /** 该订单需要的供应商（拆单结果，确定性顺序）。 */
    public List<String> planSupplierIds(String orderId) {
        return planner.plan(load(orderId)).stream().map(p -> p.supplier().supplierId()).toList();
    }

    /** 确保收货地址已解密（下采购单前一次；无采购计划则不解密）。 */
    public void ensureAddressDecrypted(String orderId) {
        OrderAggregate aggregate = load(orderId);
        if (planner.plan(aggregate).isEmpty()) {
            return;
        }
        store.updateOrder(aggregate
                .withShippingAddress(resolveAddressForPurchase(aggregate).address())
                .toDocument());
    }

    /** 单供应商下采购单（下单 + 支付）；幂等：该供应商已有采购单直接返回。 */
    public PurchaseOrder placePurchase(String orderId, String supplierId) {
        OrderAggregate aggregate = load(orderId);
        Optional<PurchaseOrder> existing = findPurchase(aggregate, supplierId);
        if (existing.isPresent()) {
            return existing.get();
        }
        PurchasePlanner.PurchasePlan plan = planner.plan(aggregate).stream()
                .filter(p -> p.supplier().supplierId().equals(supplierId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("该供应商无采购计划，无法下单: " + supplierId));
        AddressResolution resolution = resolveAddressForPurchase(aggregate);
        PurchaseResult result = purchase.createPurchase(toDraft(plan, resolution.plaintext()));
        purchase.payPurchase(result.platformPurchaseNo());
        PurchaseOrder created = buildPurchaseOrder(aggregate.order(), plan, result);
        store.updateOrder(aggregate
                .withShippingAddress(resolution.address())
                .withPurchaseOrders(List.of(created))
                .toDocument());
        return created;
    }

    /** 单供应商发货 → 回传销售平台，推进采购轴与销售履约轴；幂等：已终态直接返回。 */
    public PurchaseOrder returnShipment(String orderId, String supplierId) {
        OrderAggregate aggregate = load(orderId);
        PurchaseOrder purchaseOrder = findPurchase(aggregate, supplierId)
                .orElseThrow(() -> new IllegalStateException("该供应商尚无采购单，无法发货回传: " + supplierId));
        if (purchaseOrder.purchaseStatus() != null
                && TERMINAL_PURCHASE.contains(purchaseOrder.purchaseStatus())) {
            return purchaseOrder;
        }
        LogisticsTrace trace = purchase.fetchLogistics(purchaseOrder.platformPurchaseNo());
        List<Tracking> tracking = trace.tracking() == null ? List.of() : trace.tracking();
        if (tracking.isEmpty()) {
            return purchaseOrder;
        }
        Tracking first = tracking.get(0);
        shipment.notifyShipment(new ShipmentNotification(aggregate.order().platformOrderNo(),
                first.company(), first.trackingNo(), first.url()));
        PurchaseOrder shipped = new PurchaseOrder(purchaseOrder.purchaseOrderId(),
                purchaseOrder.orderId(), purchaseOrder.supplierRef(), purchaseOrder.platformPurchaseNo(),
                PurchaseStatus.SHIPPED, purchaseOrder.platformStatus(), purchaseOrder.platformRaw(),
                purchaseOrder.lines(), purchaseOrder.amount(), tracking, now(),
                purchaseOrder.provenance());
        OrderAggregate updated = aggregate.withPurchaseOrders(List.of(shipped));
        FulfillmentStatus derived = FulfillmentDeriver.deriveSales(updated.purchaseOrders(), updated.rmas());
        store.updateOrder(updated.withFulfillmentStatus(derived).toDocument());
        return shipped;
    }

    // ---------------- 整单（demo / 单测粒度） ----------------

    /** 拆单并逐供应商下采购单（下单 + 支付）；返回本次落库的采购单。 */
    public List<PurchaseOrder> placePurchases(String orderId) {
        List<String> supplierIds = planSupplierIds(orderId);
        if (supplierIds.isEmpty()) {
            return List.of();
        }
        ensureAddressDecrypted(orderId);
        List<PurchaseOrder> created = new ArrayList<>();
        for (String supplierId : supplierIds) {
            created.add(placePurchase(orderId, supplierId));
        }
        return List.copyOf(created);
    }

    /** 对所有采购单发货回传；返回当前全部采购单。 */
    public List<PurchaseOrder> returnShipments(String orderId) {
        List<PurchaseOrder> current = load(orderId).purchaseOrders();
        List<PurchaseOrder> updated = new ArrayList<>();
        for (PurchaseOrder purchaseOrder : current) {
            updated.add(returnShipment(orderId, purchaseOrder.supplierRef().supplierId()));
        }
        return List.copyOf(updated);
    }

    // ---------------- 内部 ----------------

    private static Optional<PurchaseOrder> findPurchase(OrderAggregate aggregate, String supplierId) {
        return aggregate.purchaseOrders().stream()
                .filter(p -> p.supplierRef().supplierId().equals(supplierId))
                .findFirst();
    }

    private AddressResolution resolveAddressForPurchase(OrderAggregate aggregate) {
        ShippingAddress current = aggregate.order().shippingAddress();
        if (current != null && current.state() == ShippingAddressState.DECRYPTED
                && current.encryptedPayload() != null) {
            return new AddressResolution(current, cipher.decrypt(current.encryptedPayload()));
        }
        Order order = aggregate.order();
        DecryptedAddress plaintext = address.decryptAddress(
                new AddressRef(order.platform(), order.platformOrderNo(), null));
        ShippingAddress decrypted = new ShippingAddress(ShippingAddressState.DECRYPTED,
                cipher.encrypt(plaintext), current == null ? null : current.masked(),
                "address.decryptAddress");
        return new AddressResolution(decrypted, plaintext);
    }

    private static PurchaseDraft toDraft(PurchasePlanner.PurchasePlan plan, DecryptedAddress recipient) {
        List<PurchaseDraftItem> items = plan.lines().stream()
                .map(l -> new PurchaseDraftItem(l.sourceSkuId(), l.sourceOfferId(), l.sourceSpecId(),
                        l.quantity(), l.unitPrice()))
                .toList();
        return new PurchaseDraft(plan.supplier(), items, recipient);
    }

    private PurchaseOrder buildPurchaseOrder(Order order, PurchasePlanner.PurchasePlan plan,
                                             PurchaseResult result) {
        String purchaseOrderId = purchaseOrderId(order.orderId(), plan.supplier().supplierId());
        List<PurchaseLine> lines = plan.lines().stream()
                .map(l -> new PurchaseLine("pline-" + l.orderLineId(), l.orderLineId(), l.sourceSkuId(),
                        l.quantity(), new Money(new BigDecimal(l.unitPrice().amount()),
                        l.unitPrice().currency())))
                .toList();
        Money amount = totalAmount(plan);
        Timestamps timestamps = now();
        Provenance provenance = new Provenance(ProvenanceStep.CAPTURE, null, order.orderId(),
                timestamps.createdAt(), timestamps.updatedAt());
        return new PurchaseOrder(purchaseOrderId, order.orderId(), plan.supplier(),
                result.platformPurchaseNo(), PurchaseStatus.PAID, null, result.platformRaw(), lines,
                amount, List.of(), timestamps, provenance);
    }

    /**
     * 采购单确定性 id = {@code purchase-{orderId}-{supplierId}}：与
     * {@code PurchaseRuntime.workflowIdFor(orderId, supplierId)} 同源（采购 workflowId 业务键）。
     */
    public static String purchaseOrderId(String orderId, String supplierId) {
        return "purchase-" + orderId + "-" + supplierId;
    }

    private static Money totalAmount(PurchasePlanner.PurchasePlan plan) {
        BigDecimal total = BigDecimal.ZERO;
        String currency = "CNY";
        for (PurchasePlanner.PlannedLine line : plan.lines()) {
            total = total.add(new BigDecimal(line.unitPrice().amount())
                    .multiply(BigDecimal.valueOf(line.quantity())));
            currency = line.unitPrice().currency();
        }
        return new Money(total, currency);
    }

    private OrderAggregate load(String orderId) {
        return OrderAggregate.of(store.getOrderById(orderId).orElseThrow(() -> new IllegalStateException(
                "OrderStore 无此订单聚合: " + orderId + "（订单须先经同步落库）")));
    }

    private Timestamps now() {
        String iso = clock.instant().toString();
        return new Timestamps(iso, iso);
    }

    /** 地址解密结果：落库形态（DECRYPTED + 密文）+ 明文（仅下采购单片刻使用）。 */
    private record AddressResolution(ShippingAddress address, DecryptedAddress plaintext) {
    }
}
