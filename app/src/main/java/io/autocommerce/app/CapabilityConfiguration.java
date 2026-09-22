package io.autocommerce.app;

import io.autocommerce.adapterhost.AdapterHost;
import io.autocommerce.core.contract.AddressCapability;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.PublishCapability;
import io.autocommerce.core.contract.PurchaseCapability;
import io.autocommerce.core.contract.RmaCapability;
import io.autocommerce.core.contract.ShipmentCapability;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 能力 bean 装配（specs/0007 §7.2）：把业务服务需要的 core {@code Capability} 绑定到
 * {@link AdapterHost} 解析出的平台实现。
 *
 * <p>两类绑定策略：
 * <ul>
 *   <li><b>按请求平台解析</b>（{@link OfferFetchCapability}）：{@code source_ref.platform} 每次请求
 *       都可能不同，故 bean 是"按 platform 转发"的薄适配器，不缓存具体平台；</li>
 *   <li><b>按能力类型惰性解析</b>（其余）：v1 各链路未必都有生产 Adapter，用
 *       {@link CapabilityResolver#lazy(Class)} 惰性代理，避免装配期硬失败（缺实现时**调用期**显式报错，
 *       不静默 null）。</li>
 * </ul>
 */
@Configuration
public class CapabilityConfiguration {

    @Bean
    CapabilityResolver capabilityResolver(AdapterHost adapterHost) {
        return new CapabilityResolver(adapterHost);
    }

    /** 货源采集能力：按 {@code source_ref.platform} 解析对应平台的 {@code OfferFetchCapability}。 */
    @Bean
    OfferFetchCapability offerFetchCapability(CapabilityResolver resolver) {
        return ref -> resolver.byPlatform(ref.platform(), OfferFetchCapability.class).fetchOffer(ref);
    }

    /** 铺货能力（销售侧）：v1 生产销售 Adapter 未落地，惰性解析（e2e 由 {@code fake-sales} 提供）。 */
    @Bean
    PublishCapability publishCapability(CapabilityResolver resolver) {
        return resolver.lazy(PublishCapability.class);
    }

    /** 货源采购能力（1688 侧）。 */
    @Bean
    PurchaseCapability purchaseCapability(CapabilityResolver resolver) {
        return resolver.lazy(PurchaseCapability.class);
    }

    /** 发货回传能力（销售平台侧；v1 无生产实现）。 */
    @Bean
    ShipmentCapability shipmentCapability(CapabilityResolver resolver) {
        return resolver.lazy(ShipmentCapability.class);
    }

    /** 收货地址解密能力（销售平台侧；v1 无生产实现）。 */
    @Bean
    AddressCapability addressCapability(CapabilityResolver resolver) {
        return resolver.lazy(AddressCapability.class);
    }

    /** 售后状态读取能力（销售平台侧；v1 无生产实现）。 */
    @Bean
    RmaCapability rmaCapability(CapabilityResolver resolver) {
        return resolver.lazy(RmaCapability.class);
    }
}
