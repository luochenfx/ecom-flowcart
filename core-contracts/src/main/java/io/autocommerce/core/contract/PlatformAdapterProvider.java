package io.autocommerce.core.contract;

import java.util.Set;

/**
 * 平台 Adapter SPI 装配入口（ADR-0007 / specs/0005 §4）。
 *
 * <p>每平台一个独立 Maven 模块，经 {@link java.util.ServiceLoader} 声明本接口实现；
 * 无中央注册表——core 只认能力接口，模块在 classpath 即被发现。adapter-host 装配时
 * 加载 provider 集合并按需探测能力；core 不维护任何平台清单。
 *
 * <p>能力可部分实现（Capability 接口族）：{@link #capabilities()} 声明本 provider 实际
 * 实现的能力集合；未实现的能力 = core 侧该链路不可用（不影响其它链路，见 specs/0005 §2）。
 */
public interface PlatformAdapterProvider {

    /** 平台标识（与 {@code SourceRef.platform} 对齐，如 "1688"）。 */
    String platform();

    /** 本平台已实现的能力接口集合（{@link Capability} 子类型）。 */
    Set<Class<? extends Capability>> capabilities();

    /** 按能力类型取实例；本平台未实现该能力时抛 {@link IllegalArgumentException}。 */
    <T extends Capability> T getCapability(Class<T> capabilityType);
}
