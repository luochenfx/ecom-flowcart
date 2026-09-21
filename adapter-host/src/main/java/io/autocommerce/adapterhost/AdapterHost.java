package io.autocommerce.adapterhost;

import io.autocommerce.core.contract.Capability;
import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.step.AiStep;
import io.autocommerce.core.step.AiStepProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * 平台 Adapter 与 AI Step 的 <b>Composition Root</b>（specs/0007 §7.1 / ADR-0007 / ADR-0009）。
 *
 * <p>本类经 {@link ServiceLoader} 汇总 classpath 上全部 SPI 实现：
 * <ul>
 *   <li>{@link PlatformAdapterProvider}（{@code META-INF/services/...PlatformAdapterProvider}）→ 平台 Adapter；</li>
 *   <li>{@link AiStepProvider}（{@code META-INF/services/...AiStepProvider}）→ AI Step。</li>
 * </ul>
 *
 * <p><b>只依赖 core（{@code core-contracts}）——这是插件化能成立的硬约束</b>：本类不 import 任何具体
 * Adapter（如 1688 / fake-sales），故 <b>新增平台不需要修改任何装配代码</b>。具体 Adapter 的 jar 由
 * 部署单元（{@code app}）的 runtime classpath 提供——classpath 上有什么平台就能发现什么。若本类依赖了
 * 具体 Adapter，插件化就被编译期依赖焊死了（正是本票要禁止的）。
 *
 * <p>失败语义：请求未发现的平台抛 {@link UnknownPlatformException}；请求平台未实现的能力抛
 * {@link UnsupportedCapabilityException}——<b>绝不静默返回 null</b>。
 *
 * <p>不可变：{@link #load()} 时一次性发现并建索引，之后只读，可安全并发共享（无内部可变状态）。
 */
public final class AdapterHost {

    private final Map<String, PlatformAdapterProvider> providersByPlatform;
    private final List<AiStep> aiSteps;

    private AdapterHost(Map<String, PlatformAdapterProvider> providersByPlatform, List<AiStep> aiSteps) {
        this.providersByPlatform = providersByPlatform;
        this.aiSteps = aiSteps;
    }

    /**
     * 经线程上下文 {@link ClassLoader}（{@link Thread#currentThread()} 的 context classloader）发现并装配。
     *
     * <p>生产路径（Spring / app 装配）用本方法；测试若需隔离 classpath 用 {@link #load(ClassLoader)}。
     */
    public static AdapterHost load() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = AdapterHost.class.getClassLoader();
        }
        return load(loader);
    }

    /**
     * 从指定 {@link ClassLoader} 发现并装配——便于测试用自定义 ClassLoader 隔离 classpath。
     *
     * @param classLoader SPI 发现源；不得为 null
     */
    public static AdapterHost load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");

        Map<String, PlatformAdapterProvider> providers = new LinkedHashMap<>();
        for (PlatformAdapterProvider provider : ServiceLoader.load(PlatformAdapterProvider.class, classLoader)) {
            String platform = provider.platform();
            PlatformAdapterProvider existing = providers.putIfAbsent(platform, provider);
            if (existing != null) {
                throw new IllegalStateException(
                        "adapter-host 发现重复平台: \"" + platform + "\"（"
                                + existing.getClass().getName() + " 与 " + provider.getClass().getName()
                                + "）。一个平台只应有一个 AdapterProvider。");
            }
        }

        List<AiStep> steps = new ArrayList<>();
        for (AiStepProvider provider : ServiceLoader.load(AiStepProvider.class, classLoader)) {
            steps.addAll(provider.steps());
        }

        return new AdapterHost(providers, List.copyOf(steps));
    }

    /** 已发现平台清单（不可变、稳定顺序 = 发现顺序）。 */
    public Set<String> platforms() {
        return Collections.unmodifiableSet(providersByPlatform.keySet());
    }

    /** 指定平台实现的能力接口集合；平台未发现时抛 {@link UnknownPlatformException}。 */
    public Set<Class<? extends Capability>> capabilities(String platform) {
        return provider(platform).capabilities();
    }

    /**
     * 按 {@code (platform, Capability 类型)} 取能力实例。
     *
     * @throws UnknownPlatformException       平台未发现
     * @throws UnsupportedCapabilityException 平台已发现但未实现该能力
     */
    public <T extends Capability> T capability(String platform, Class<T> type) {
        Objects.requireNonNull(type, "type");
        PlatformAdapterProvider provider = provider(platform);
        if (!provider.capabilities().contains(type)) {
            throw new UnsupportedCapabilityException(platform, type, provider.capabilities());
        }
        return provider.getCapability(type);
    }

    /** 汇总各 {@link AiStepProvider} 声明的全部 AI Step（不可变、稳定顺序 = 发现顺序）。 */
    public List<AiStep> aiSteps() {
        return aiSteps;
    }

    private PlatformAdapterProvider provider(String platform) {
        Objects.requireNonNull(platform, "platform");
        PlatformAdapterProvider provider = providersByPlatform.get(platform);
        if (provider == null) {
            throw new UnknownPlatformException(platform, providersByPlatform.keySet());
        }
        return provider;
    }
}
