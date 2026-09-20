package io.autocommerce.order.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.autocommerce.core.catalog.model.Provenance;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.contract.dto.DecryptedAddress;
import io.autocommerce.core.contract.dto.OrderSyncPage;
import io.autocommerce.core.contract.dto.SyncCursor;
import io.autocommerce.core.order.model.Amounts;
import io.autocommerce.core.order.model.ExternalItemRef;
import io.autocommerce.core.order.model.FulfillmentStatus;
import io.autocommerce.core.order.model.LineRef;
import io.autocommerce.core.order.model.LineSnapshot;
import io.autocommerce.core.order.model.Money;
import io.autocommerce.core.order.model.Order;
import io.autocommerce.core.order.model.OrderRma;
import io.autocommerce.core.order.model.OrderSnapshot;
import io.autocommerce.core.order.model.RmaLineRef;
import io.autocommerce.core.order.model.RmaRef;
import io.autocommerce.core.order.model.RmaStatus;
import io.autocommerce.core.order.model.RmaType;
import io.autocommerce.core.order.model.ShippingAddress;
import io.autocommerce.core.order.model.ShippingAddressMask;
import io.autocommerce.core.order.model.ShippingAddressState;
import io.autocommerce.core.order.model.SupplierRef;
import io.autocommerce.core.order.model.Timestamps;
import io.autocommerce.order.purchase.SourcingRefResolver;
import io.autocommerce.order.rma.OrderRmaSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 订单链测试 fixture（demo / 单测共用）。构造一张 fixture 销售订单：两个快照行分属<b>两个不同供应商</b>
 * （用于演示 Order → 1:N 跨供应商拆单），并带一条 REFUND 售后单（用于演示 RMA 读）。
 *
 * <p>物理字段形态刻意贴近真实平台（淘宝 / 拼多多）：<b>金额粒度以平台 API 能给为准</b>（单行单价 × 数量
 * 与商品总额分列、优惠单列），物流取文本公司名 + 运单号——这套形态过 {@code order.schema.json} 契约门
 * （Testing §1），落实 #22 AC「fog 实测回填：回填即过 order schema 契约校验」。真实平台字段校准
 * （拼多多订单结构 / 物流公司枚举 / 退款金额粒度）随 #23 Adapter 全能力收口用实测响应回填。
 */
public final class OrderFixtures {

    public static final String CHANNEL_ID = "fixture-shop-a";
    public static final String PLATFORM = "fixture-sales";
    public static final String PLATFORM_ORDER_NO = "202609090001";
    public static final String ORDER_ID = "order-" + PLATFORM + "-" + PLATFORM_ORDER_NO;
    public static final String SNAPSHOT_ID = "snap-fixture-5001";

    public static final String LINE_ID_1 = "oline-6001";
    public static final String LINE_ID_2 = "oline-6002";
    public static final String SNAPSHOT_LINE_1 = "sline-8001";
    public static final String SNAPSHOT_LINE_2 = "sline-8002";

    public static final String SUPPLIER_ID_1 = "1688-supplier-771";
    public static final String SUPPLIER_ID_2 = "1688-supplier-882";
    public static final String RMA_ID = "rma-7001";
    public static final String PLATFORM_RMA_ID = "FX-refund-9001";

    private static final String CAPTURED_AT = "2026-09-09T10:05:00Z";
    private static final String CREATED_AT = "2026-09-09T10:06:00Z";
    private static final String STATUS_TIME = "2026-09-09T10:05:30Z";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrderFixtures() {
    }

    /** OrderSyncCapability 拉取一页：一张订单 + 其建单快照 + 推进后的游标。 */
    public static OrderSyncPage page() {
        ObjectNode nextCursor = MAPPER.createObjectNode();
        nextCursor.put("last_modified", STATUS_TIME);
        nextCursor.put("page", 1);
        return new OrderSyncPage(List.of(order()), List.of(snapshot()),
                new SyncCursor(CHANNEL_ID, nextCursor));
    }

    /** 只有第二页（空页：游标耗尽信号）——用于演示 pullAll 的终止。 */
    public static OrderSyncPage emptyPage() {
        return new OrderSyncPage(List.of(), List.of(), null);
    }

    public static Order order() {
        return new Order(ORDER_ID, CHANNEL_ID, PLATFORM, PLATFORM_ORDER_NO,
                "WAIT_SELLER_SEND_GOODS", STATUS_TIME, orderRaw(),
                FulfillmentStatus.AWAITING_PURCHASE, SNAPSHOT_ID,
                new ShippingAddress(ShippingAddressState.MASKED, null, masked(), null),
                "buyer_***123",
                List.of(new LineRef(LINE_ID_1), new LineRef(LINE_ID_2)),
                List.of(new RmaRef(RMA_ID)),
                new Timestamps(CREATED_AT, CREATED_AT),
                provenance("channel-sync:" + CHANNEL_ID));
    }

    public static OrderSnapshot snapshot() {
        return new OrderSnapshot(SNAPSHOT_ID, ORDER_ID, CAPTURED_AT,
                new Amounts(
                        new Money(new BigDecimal("158.00"), "CNY"),
                        new Money(new BigDecimal("0.00"), "CNY"),
                        new Money(new BigDecimal("10.00"), "CNY"),
                        new Money(new BigDecimal("148.00"), "CNY"),
                        "CNY"),
                List.of(lineSnapshot1(), lineSnapshot2()),
                masked(),
                snapshotRaw(),
                provenance(ORDER_ID));
    }

    public static OrderRma rma() {
        return new OrderRma(RMA_ID, ORDER_ID, RmaType.REFUND, PLATFORM_RMA_ID,
                RmaStatus.WAITING_SELLER, "REFUND_WAIT_SELLER_AGREE",
                List.of(new RmaLineRef(LINE_ID_1)),
                new Money(new BigDecimal("79.00"), "CNY"),
                "商品与描述不符",
                new Timestamps("2026-09-09T14:00:00Z", "2026-09-09T14:00:00Z"),
                provenance(ORDER_ID));
    }

    /** RMA 发现源（fixture）：任意订单都挂这条 fixture 售后单。 */
    public static OrderRmaSource rmaSource() {
        return order -> ORDER_ID.equals(order.orderId()) ? List.of(rma()) : List.of();
    }

    /** 货源解析（fixture）：两行分属两供应商 → 触发跨供应商拆单。 */
    public static SourcingRefResolver sourcingResolver() {
        Map<String, SourcingRefResolver.SourcingRef> byMyRef = Map.of(
                "sku-3001", new SourcingRefResolver.SourcingRef(
                        new SupplierRef("1688", SUPPLIER_ID_1, "深圳华强北数码批发"),
                        "offer-1001", "spec-aaaa0001", "src-sku-3001",
                        new io.autocommerce.core.catalog.model.Money("45.90", "CNY")),
                "sku-3002", new SourcingRefResolver.SourcingRef(
                        new SupplierRef("1688", SUPPLIER_ID_2, "东莞线材工厂店"),
                        "offer-2001", "spec-bbbb0002", "src-sku-3002",
                        new io.autocommerce.core.catalog.model.Money("12.50", "CNY")));
        return lineSnapshot -> Optional.ofNullable(byMyRef.get(lineSnapshot.myRef()));
    }

    /** 平台解密 API 返回的明文地址（下采购单前取回）。 */
    public static DecryptedAddress decryptedAddress() {
        return new DecryptedAddress("张三", "13800001234", "中国", "广东省", "深圳市", "南山区",
                "科技园路 100 号", "518000");
    }

    public static ShippingAddressMask masked() {
        return new ShippingAddressMask("中国", "广东省", "深圳市", "南山区",
                "科技园***路 100 号", "518000", "张*", "138****1234");
    }

    // ---------------- 内部 ----------------

    private static LineSnapshot lineSnapshot1() {
        return new LineSnapshot(SNAPSHOT_LINE_1,
                new ExternalItemRef("fx-item-A", "fx-sku-A"), "sku-3001",
                "便携蓝牙音箱 迷你无线低音炮", "黑色 M",
                new Money(new BigDecimal("79.00"), "CNY"), 1,
                new Money(new BigDecimal("79.00"), "CNY"), null);
    }

    private static LineSnapshot lineSnapshot2() {
        return new LineSnapshot(SNAPSHOT_LINE_2,
                new ExternalItemRef("fx-item-B", "fx-sku-B"), "sku-3002",
                "USB-C 快充数据线 1m", "1m",
                new Money(new BigDecimal("79.00"), "CNY"), 1,
                new Money(new BigDecimal("79.00"), "CNY"), null);
    }

    private static JsonNode orderRaw() {
        ObjectNode raw = MAPPER.createObjectNode();
        raw.put("tid", PLATFORM_ORDER_NO);
        raw.put("buyer_rate", true);
        return raw;
    }

    private static JsonNode snapshotRaw() {
        ObjectNode raw = MAPPER.createObjectNode();
        raw.put("snapshot_url", "https://trade.example.com/detail/" + PLATFORM_ORDER_NO);
        return raw;
    }

    private static Provenance provenance(String parentRef) {
        return new Provenance(ProvenanceStep.CAPTURE, null, parentRef, CAPTURED_AT, CAPTURED_AT);
    }
}
