package io.autocommerce.adapter.ali1688;

import io.autocommerce.core.contract.Capability;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.PlatformAdapterProvider;

import java.util.Set;

/**
 * 1688 平台 Adapter SPI 装配入口（ADR-0007 / specs/0005 §4）。
 *
 * <p>经 META-INF/services/io.autocommerce.core.contract.PlatformAdapterProvider 声明；
 * 无中央注册表——core 只认能力接口，本模块在 classpath 即被发现。#19 子集只实现
 * OfferFetchCapability；能力清单随 #23（Purchase / Shipment / Auth…）扩展。
 */
public final class Ali1688AdapterProvider implements PlatformAdapterProvider {

    @Override
    public String platform() {
        return "1688";
    }

    @Override
    public Set<Class<? extends Capability>> capabilities() {
        return Set.of(OfferFetchCapability.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Capability> T getCapability(Class<T> capabilityType) {
        if (capabilityType == OfferFetchCapability.class) {
            return (T) new Ali1688OfferFetch();
        }
        throw new IllegalArgumentException(
                "adapter-1688 未实现能力: " + capabilityType.getName() + "（实现清单见 capabilities()）");
    }
}
