package io.autocommerce.adapter.ali1688;

import io.autocommerce.core.contract.AuthCapability;
import io.autocommerce.core.contract.Capability;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.contract.PurchaseCapability;
import io.autocommerce.core.contract.ShipmentCapability;

import java.util.Set;

/**
 * 1688 平台 Adapter SPI 装配入口（ADR-0007 / specs/0005 §4）。
 *
 * <p>经 META-INF/services/io.autocommerce.core.contract.PlatformAdapterProvider 声明；
 * 无中央注册表——core 只认能力接口，本模块在 classpath 即被发现。
 *
 * <p><b>能力清单 = {OfferFetch, Purchase, Auth}</b>。<b>不实现 {@link ShipmentCapability}</b>：
 * 其语义是「供应商物流单号 → 销售平台」（{@code notifyShipment} 携带销售平台订单号），只属
 * <b>销售平台侧</b> Adapter（淘宝 / 拼多多 / 速卖通）；1688 是货源侧，不存在"向销售平台回传发货"
 * 这一动作，其物流信息由 {@link PurchaseCapability#fetchLogistics(String)} 承担。
 * 详见 ADR-0007「能力归属侧别纠偏」。
 *
 * <p>两种装配形态：
 * <ul>
 *   <li><b>无参</b>（Java SPI 发现）：无凭据——OfferFetch 走 #19 的 {@code source_ref.url} 直连；
 *       Purchase / Auth 调用时报缺凭据（NON_RETRYABLE）；</li>
 *   <li><b>带 {@link Ali1688AdapterConfig}</b>：adapter-host 拿到 channel 解密凭据后装配，
 *       采购 / 认证链路走 param2 签名网关。</li>
 * </ul>
 */
public final class Ali1688AdapterProvider implements PlatformAdapterProvider {

    private final Ali1688AdapterConfig config;

    public Ali1688AdapterProvider() {
        this(Ali1688AdapterConfig.unconfigured());
    }

    public Ali1688AdapterProvider(Ali1688AdapterConfig config) {
        this.config = config;
    }

    @Override
    public String platform() {
        return "1688";
    }

    @Override
    public Set<Class<? extends Capability>> capabilities() {
        return Set.of(OfferFetchCapability.class, PurchaseCapability.class, AuthCapability.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Capability> T getCapability(Class<T> capabilityType) {
        if (capabilityType == OfferFetchCapability.class) {
            return (T) new Ali1688OfferFetch();
        }
        if (capabilityType == PurchaseCapability.class) {
            return (T) new Ali1688Purchase(config);
        }
        if (capabilityType == AuthCapability.class) {
            return (T) new Ali1688Auth(config);
        }
        throw new IllegalArgumentException(
                "adapter-1688 未实现能力: " + capabilityType.getName() + "（实现清单见 capabilities()）");
    }
}
