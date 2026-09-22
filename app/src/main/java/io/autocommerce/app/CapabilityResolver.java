package io.autocommerce.app;

import io.autocommerce.adapterhost.AdapterHost;
import io.autocommerce.core.contract.Capability;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从 {@link AdapterHost} 解析能力实例的装配助手（app 层，不改 adapter-host）。
 *
 * <h2>为什么需要"懒解析"</h2>
 * v1 的部署 classpath 上并非每个能力都有平台实现（例如：货源侧 {@code 1688} 提供
 * {@code OfferFetch/Purchase/Auth}；销售侧 v1 只有测试 Adapter {@code fake-sales} 提供
 * {@code PublishCapability}；订单侧能力暂无生产 Adapter）。若在装配期**急切**取能力，缺实现的链路会在
 * 容器启动即失败——而缺哪些能力取决于 classpath / 运行环境（e2e 注入 {@code fake-sales}）。故本类对
 * "按类型解析"的能力提供 {@link #lazy(Class)}：返回一个**惰性代理**，首次方法调用时才向
 * {@link AdapterHost} 解析——把"能力缺失"从**启动期失败**降级为**调用期显式失败**（绝不静默返回
 * null，与 {@code AdapterHost} 契约一致）。
 *
 * <p>按平台解析的能力（{@code OfferFetch} 随请求的 {@code source_ref.platform} 变化）不合适做单例代理，
 * 由 {@code CapabilityConfiguration} 用 lambda 每次按请求平台解析。
 */
public final class CapabilityResolver {

    private final AdapterHost adapterHost;

    public CapabilityResolver(AdapterHost adapterHost) {
        this.adapterHost = adapterHost;
    }

    /** 按平台 + 能力类型解析（平台未发现 / 未实现该能力 → 明确失败，见 adapter-host 契约）。 */
    public <T extends Capability> T byPlatform(String platform, Class<T> type) {
        return adapterHost.capability(platform, type);
    }

    /**
     * 按能力类型解析：取**首个**声明实现该能力的平台（平台清单稳定顺序 = SPI 发现顺序）。
     * 无任何平台实现该能力 → 明确失败（不静默 null）。
     */
    public <T extends Capability> T single(Class<T> type) {
        return adapterHost.platforms().stream()
                .filter(platform -> adapterHost.capabilities(platform).contains(type))
                .findFirst()
                .map(platform -> adapterHost.capability(platform, type))
                .orElseThrow(() -> new IllegalStateException(
                        "AdapterHost 未发现提供能力 " + type.getName() + " 的平台（已发现平台: "
                                + adapterHost.platforms() + "）。请确认对应 Adapter 在 runtime classpath 上。"));
    }

    /**
     * 惰性代理：首次方法调用时才 {@link #single(Class)} 解析，并**记忆化**（后续调用复用同一实例）。
     * 用于"v1 可能无生产实现"的能力（如 {@code PublishCapability}）——避免装配期硬失败。
     *
     * <p>记忆化是必要的：能力实现可能带内部状态（如测试 Adapter 的脚本化结局序列），每次调用都
     * 新建实例会丢失该状态。这里的语义 = "首次使用时解析一次的平台能力单例"。
     */
    @SuppressWarnings("unchecked")
    public <T extends Capability> T lazy(Class<T> type) {
        AtomicReference<T> resolved = new AtomicReference<>();
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "LazyCapabilityProxy(" + type.getSimpleName() + ")";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    T target = resolved.get();
                    if (target == null) {
                        target = single(type);
                        resolved.compareAndSet(null, target);
                        target = resolved.get();
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }
}
