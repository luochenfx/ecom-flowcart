package io.autocommerce.order.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.autocommerce.core.contract.Capability;
import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.contract.PurchaseCapability;
import io.autocommerce.core.contract.dto.LogisticsTrace;
import io.autocommerce.core.contract.dto.PurchaseDraft;
import io.autocommerce.core.contract.dto.PurchaseResult;
import io.autocommerce.core.order.model.Tracking;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * fixture 假<b>货源侧</b>（1688）平台 Adapter（#22 demo）：实现 {@link PurchaseCapability}
 * （下单 / 支付 / 撤销 / 物流），模拟 1688 {@code alibaba.trade.fastCreateOrder}（flow=saleproxy）四段调用。
 *
 * <p>归属侧别：与真实 adapter-1688 同侧——只实现货源侧能力，<b>不实现</b> {@link
 * io.autocommerce.core.contract.ShipmentCapability}（发货回传属销售侧，见 ADR-0007 归属侧别纠偏）。
 * 单独一个 provider（平台标识 {@code fixture-1688}）避免与真实 adapter-1688 在 SPI classpath 上撞名。
 *
 * <p>调用留痕：下单草稿 / 支付单号 / 物流查询单号。
 */
public final class FixtureSourcePlatformAdapter implements PlatformAdapterProvider, PurchaseCapability {

    public static final String PLATFORM = "fixture-1688";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<PurchaseDraft> drafts = new ArrayList<>();
    private final List<String> paid = new ArrayList<>();
    private final List<String> logisticsQueries = new ArrayList<>();

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public Set<Class<? extends Capability>> capabilities() {
        return Set.of(PurchaseCapability.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Capability> T getCapability(Class<T> capabilityType) {
        if (capabilityType == PurchaseCapability.class) {
            return (T) this;
        }
        throw new IllegalArgumentException("fixture 货源侧 Adapter 未实现能力: " + capabilityType.getName());
    }

    @Override
    public PurchaseResult createPurchase(PurchaseDraft draft) {
        drafts.add(draft);
        ObjectNode raw = MAPPER.createObjectNode();
        raw.put("flow", "saleproxy");
        raw.put("outOrderId", draft.supplierRef().supplierId());
        raw.put("amount", "58.40");
        return new PurchaseResult("1688PO-" + draft.supplierRef().supplierId(), raw);
    }

    @Override
    public void payPurchase(String platformPurchaseNo) {
        paid.add(platformPurchaseNo);
    }

    @Override
    public void cancelPurchase(String platformPurchaseNo) {
        // 未付款撤销：demo 不触发
    }

    @Override
    public LogisticsTrace fetchLogistics(String platformPurchaseNo) {
        logisticsQueries.add(platformPurchaseNo);
        String trackingNo = "SF" + platformPurchaseNo.replaceAll("\\D", "");
        return new LogisticsTrace(platformPurchaseNo, List.of(
                new Tracking("顺丰速运", trackingNo, "运输中",
                        "https://www.sf-express.com/waybill/" + trackingNo)));
    }

    public List<PurchaseDraft> drafts() {
        return List.copyOf(drafts);
    }

    public List<String> paid() {
        return List.copyOf(paid);
    }

    public List<String> logisticsQueries() {
        return List.copyOf(logisticsQueries);
    }
}
