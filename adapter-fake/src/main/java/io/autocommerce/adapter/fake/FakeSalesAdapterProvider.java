package io.autocommerce.adapter.fake;

import io.autocommerce.core.contract.Capability;
import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.contract.PublishCapability;
import io.autocommerce.core.contract.ShipmentCapability;

import java.util.Set;

/**
 * 测试 Adapter 的 SPI 装配入口（specs/0007 §9.1；CONTEXT.md「测试 Adapter」）。
 *
 * <p>经 META-INF/services/io.autocommerce.core.contract.PlatformAdapterProvider 声明；
 * 与真实 Adapter（{@code adapter-1688}）走<b>完全相同</b>的 SPI 路径——无中央注册表，
 * 模块在 classpath 即被发现。这是本模块的核心价值：它同时验证了"装配根（adapter-host）
 * 能否把销售平台 Adapter 装进来"。
 *
 * <p><b>能力清单 = {PublishCapability}</b>（销售侧）。不实现
 * {@link io.autocommerce.core.contract.OfferFetchCapability} /
 * {@link io.autocommerce.core.contract.PurchaseCapability} /
 * {@link io.autocommerce.core.contract.AuthCapability}
 * （货源侧，属 1688），也<b>不实现 {@link ShipmentCapability}</b>（本票不做）。
 * 与 {@code adapter-1688} 的 {@code {OfferFetch, Purchase, Auth}} <b>对称且互斥</b>：
 * 前者是货源侧、后者是销售侧，一个平台 Adapter 只归属一侧。
 *
 * <p>行为可控：底层 {@link FakeSalesPublish} 支持脚本化四种处置
 * （{@code PUBLISHED} / {@code AMBIGUOUS} / {@code REJECTED} / {@code RETRYABLE}），
 * 供端到端 demo 驱动铺货状态机全路径（成功 / 歧义挂起-裁定 / 业务拒绝 / 可重试）。
 *
 * <h2>能力实例的记忆化（每 provider 一个 {@link FakeSalesPublish} 单例）</h2>
 * {@link #getCapability(Class)} 对同一能力类型<b>恒返回同一实例</b>（构造期创建并缓存），而非每次
 * {@code new}。这不是优化，而是正确性要求：{@link FakeSalesPublish} 带内部状态（脚本化结局序列 +
 * 调用留痕），而装配侧的 {@code CapabilityResolver.lazy(...)} 会缓存"首次解析到的能力实例"——
 * 若本 provider 每次返回新实例，则"测试从 {@code AdapterHost} 拿到并 {@code script(...)} 的那个实例"
 * 与"惰性代理内部实际调用的那个实例"不是同一个，脚本永不生效（歧义挂起路径无法落地）。
 * 恒返回同一实例后，二者重合，脚本与留痕对装配路径可见。
 *
 * <p>与 SPI 契约一致（{@link PlatformAdapterProvider#getCapability(Class)} 允许返回缓存实例）；
 * 与 {@code adapter-1688} 的 provider 形态对称——那里每次新建是因 1688 能力<b>无内部状态</b>，
 * 本模块的能力<b>有状态</b>，故必须单例。
 */
public final class FakeSalesAdapterProvider implements PlatformAdapterProvider {

    /** 平台标识：与 specs/0007 §9.1 一致（e2e profile 下 app 的装配指向本平台）。 */
    public static final String PLATFORM = "fake-sales";

    /** 构造期创建、全生命周期复用的铺货能力单例（见类 javadoc「能力实例的记忆化」）。 */
    private final PublishCapability publishCapability = new FakeSalesPublish();

    /** SPI 装配用无参构造（行为默认 PUBLISHED；脚本经 {@link #script} 追加）。 */
    public FakeSalesAdapterProvider() {
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public Set<Class<? extends Capability>> capabilities() {
        return Set.of(PublishCapability.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Capability> T getCapability(Class<T> capabilityType) {
        if (capabilityType == PublishCapability.class) {
            return (T) publishCapability;
        }
        throw new IllegalArgumentException(
                "adapter-fake 未实现能力: " + capabilityType.getName() + "（实现清单见 capabilities()）");
    }
}
