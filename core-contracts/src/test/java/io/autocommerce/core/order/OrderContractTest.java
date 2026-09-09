package io.autocommerce.core.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.order.model.FulfillmentStatus;
import io.autocommerce.core.order.model.Order;
import io.autocommerce.core.order.model.OrderModel;
import io.autocommerce.core.order.model.OrderSnapshot;
import io.autocommerce.core.order.model.PurchaseOrder;
import io.autocommerce.core.order.model.PurchaseStatus;
import io.autocommerce.core.order.model.RmaStatus;
import io.autocommerce.core.order.model.RmaType;
import io.autocommerce.core.order.model.ShippingAddressState;
import io.autocommerce.core.testutil.ContractAssertions;
import io.autocommerce.core.testutil.ContractObjectMapper;
import io.autocommerce.core.testutil.ContractSchemas;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testing Decisions §1 双向契约测试（order 域）。order.schema.json 跨文件引用
 * product-catalog 的 Provenance def——schema 校验路径经 ContractSchemas.order() 内联展开。
 */
class OrderContractTest {

    private static final String GOLDEN = "/fixtures/order.json";

    private final ObjectMapper mapper = ContractObjectMapper.create();

    @Test
    void jsonToModel_keepsAllFields() throws Exception {
        OrderModel model = mapper.treeToValue(golden(), OrderModel.class);

        assertThat(model.schemaVersion()).isEqualTo("0.1.0");
        assertThat(model.orders()).hasSize(1);
        assertThat(model.orderLines()).hasSize(2);
        assertThat(model.orderSnapshots()).hasSize(1);
        assertThat(model.purchaseOrders()).hasSize(1);
        assertThat(model.rmas()).hasSize(1);
        assertThat(model.channelSyncStates()).hasSize(1);

        Order order = model.orders().get(0);
        assertThat(order.orderId()).isEqualTo("order-taobao-202609090001");
        assertThat(order.channelId()).isEqualTo("taobao-shop-a");
        assertThat(order.platform()).isEqualTo("taobao");
        assertThat(order.platformOrderNo()).isEqualTo("TB202609090001");
        assertThat(order.platformStatus()).isEqualTo("WAIT_SELLER_SEND_GOODS");
        assertThat(order.fulfillmentStatus()).isEqualTo(FulfillmentStatus.AWAITING_PURCHASE);
        assertThat(order.snapshotId()).isEqualTo("snap-5001");
        assertThat(order.platformRaw().isObject()).isTrue();
        assertThat(order.shippingAddress().state()).isEqualTo(ShippingAddressState.MASKED);
        // encryptedPayload 为 null（未解密），masked 保留脱敏值
        assertThat(order.shippingAddress().encryptedPayload()).isNull();
        assertThat(order.shippingAddress().masked().receiverPhone()).isEqualTo("138****1234");
        assertThat(order.buyer()).isEqualTo("buyer_***123");
        assertThat(order.lines()).hasSize(2);
        assertThat(order.rmas()).hasSize(1);
        assertThat(order.provenance().createdByStep()).isEqualTo(ProvenanceStep.CAPTURE);

        OrderSnapshot snapshot = model.orderSnapshots().get(0);
        assertThat(snapshot.snapshotId()).isEqualTo("snap-5001");
        // order Money.amount 为 number → BigDecimal
        assertThat(snapshot.amounts().paymentAmount().amount()).isEqualByComparingTo(new BigDecimal("148.0"));
        assertThat(snapshot.amounts().goodsAmount().currency()).isEqualTo("CNY");
        assertThat(snapshot.lineSnapshots()).hasSize(2);
        assertThat(snapshot.lineSnapshots().get(0).externalItemRef().itemId()).isEqualTo("tb-item-6688990011");
        assertThat(snapshot.lineSnapshots().get(0).quantity()).isEqualTo(1);
        assertThat(snapshot.lineSnapshots().get(1).myRef()).isNull();
        assertThat(snapshot.shippingAddressMask().province()).isEqualTo("广东省");
        assertThat(snapshot.platformRaw().get("snapshot_url").asText())
                .isEqualTo("https://trade.taobao.com/detail/TB202609090001");

        PurchaseOrder po = model.purchaseOrders().get(0);
        assertThat(po.purchaseOrderId()).isEqualTo("purchase-9001");
        assertThat(po.supplierRef().platform()).isEqualTo("1688");
        assertThat(po.supplierRef().supplierId()).isEqualTo("1688-supplier-771");
        assertThat(po.purchaseStatus()).isEqualTo(PurchaseStatus.PAID);
        assertThat(po.lines().get(0).sourceSkuRef()).isEqualTo("1688-sku-3001");
        assertThat(po.amount().amount()).isEqualByComparingTo(new BigDecimal("45.9"));
        assertThat(po.tracking().get(0).trackingNo()).isEqualTo("SF1234567890");

        assertThat(model.rmas().get(0).rmaId()).isEqualTo("rma-7001");
        assertThat(model.rmas().get(0).type()).isEqualTo(RmaType.REFUND);
        assertThat(model.rmas().get(0).rmaStatus()).isEqualTo(RmaStatus.WAITING_SELLER);
        assertThat(model.rmas().get(0).amount().amount()).isEqualByComparingTo(new BigDecimal("79.0"));

        assertThat(model.channelSyncStates().get(0).channelId()).isEqualTo("taobao-shop-a");
        assertThat(model.channelSyncStates().get(0).cursor().get("page").asInt()).isEqualTo(3);
    }

    @Test
    void modelToJson_passesSchema_andRoundTripsExactly() throws Exception {
        OrderModel model = mapper.treeToValue(golden(), OrderModel.class);

        JsonNode json = mapper.valueToTree(model);

        // 跨文件 $ref（Provenance）内联展开后的 order schema 校验
        ContractAssertions.assertValid(ContractSchemas.order(), json, "OrderModel 文档");
        ContractAssertions.assertSemanticallyEquals(golden(), json, "OrderModel 文档");
    }

    private JsonNode golden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(GOLDEN)) {
            assertThat(in).as("golden fixture 缺失: %s", GOLDEN).isNotNull();
            return mapper.readTree(in);
        }
    }
}
