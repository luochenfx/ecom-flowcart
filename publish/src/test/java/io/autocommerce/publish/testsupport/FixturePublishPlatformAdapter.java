package io.autocommerce.publish.testsupport;

import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.Capability;
import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.contract.PublishCapability;
import io.autocommerce.core.contract.dto.PlatformItemRef;
import io.autocommerce.core.contract.dto.PublishResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * fixture 假<b>销售侧</b>铺货 Adapter（#21 demo）：实现 {@link PublishCapability}（add + reconcile），
 * 可脚本化注入四种 add 结局与 reconcile 序列，用于驱动铺货状态机全路径（成功 / 歧义 / 业务拒绝 /
 * 可重试 / 缺陷）。
 *
 * <p>为什么放 publish 模块 test 作用域：fixture 只实现 core 能力接口，不含任何平台 SDK / 模型类
 * （禁环②）；也<b>不塞进 adapter-1688</b>——那是真实 Adapter。随 publish test-jar 暴露给
 * worker-runtime 端到端 demo 复用（经 {@code META-INF/services} SPI 装配）。
 *
 * <p>与真实 Adapter 的差别：这里以进程内脚本替代 WireMock HTTP 网关——fixture <b>本身就是</b>假
 * Adapter，HTTP 管线（签名 / 限流 / 错误映射）属真实 Adapter；本 demo 聚焦铺货域编排
 * （可重入 → reconcile-first + add →（歧义挂起）→ 收敛）。
 *
 * <p>调用留痕（addCalls / reconcileCalls）供测试断言"到底有没有真的重发 / 重复 add"。
 */
public final class FixturePublishPlatformAdapter implements PlatformAdapterProvider, PublishCapability {

    /** 单次 add 的结局脚本。 */
    public enum AddBehavior {
        /** 成功返回平台商品引用。 */
        SUCCESS,
        /** 超时歧义（AMBIGUOUS）。 */
        AMBIGUOUS,
        /** 业务拒绝（NON_RETRYABLE）。 */
        NON_RETRYABLE,
        /** 临时故障（RETRYABLE）。 */
        RETRYABLE,
        /** 非 AdapterException 缺陷（= bug）。 */
        BUG
    }

    public static final String PLATFORM = PublishFixtures.PLATFORM;

    private final List<AddBehavior> addScript = new ArrayList<>();
    private AddBehavior addFallback = AddBehavior.SUCCESS;
    private final List<Optional<PlatformItemRef>> reconcileScript = new ArrayList<>();
    private Optional<PlatformItemRef> reconcileFallback = Optional.empty();
    private String platformItemId = "fx-item-1";
    private String platformItemUrl = "https://fixture.example.com/item/fx-item-1";

    private final List<Listing> addCalls = new ArrayList<>();
    private final List<String> reconcileCalls = new ArrayList<>();

    /** SPI 装配用无参构造。 */
    public FixturePublishPlatformAdapter() {
    }

    /** 追加 add 结局脚本（按调用次序消费；用尽后回落到 {@link #addFallback}）。 */
    public FixturePublishPlatformAdapter script(AddBehavior... behaviors) {
        addScript.addAll(List.of(behaviors));
        return this;
    }

    /** 脚本用尽后的 add 结局（默认 SUCCESS）。 */
    public FixturePublishPlatformAdapter addFallback(AddBehavior behavior) {
        this.addFallback = behavior;
        return this;
    }

    /** 追加 reconcile 结果脚本（null 元素 = 无果 / 未实现）。 */
    public FixturePublishPlatformAdapter scriptReconcile(PlatformItemRef... refs) {
        for (PlatformItemRef ref : refs) {
            reconcileScript.add(Optional.ofNullable(ref));
        }
        return this;
    }

    /** reconcile 脚本用尽后的回落结果（默认 empty = 未实现 / 无果）。 */
    public FixturePublishPlatformAdapter reconcileFallback(PlatformItemRef ref) {
        this.reconcileFallback = Optional.ofNullable(ref);
        return this;
    }

    /** 配置 SUCCESS 结局返回的平台商品引用。 */
    public FixturePublishPlatformAdapter platformItem(String id, String url) {
        this.platformItemId = id;
        this.platformItemUrl = url;
        return this;
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
            return (T) this;
        }
        throw new IllegalArgumentException("fixture 铺货 Adapter 未实现能力: " + capabilityType.getName());
    }

    @Override
    public PublishResult add(Listing listing) {
        addCalls.add(listing);
        AddBehavior behavior = addScript.isEmpty() ? addFallback : addScript.remove(0);
        return switch (behavior) {
            case SUCCESS -> new PublishResult(listing.listingId(), platformItemId, platformItemUrl);
            case AMBIGUOUS -> throw AdapterException.ambiguous("FX_TIMEOUT", "add 请求超时，不知是否生效");
            case NON_RETRYABLE -> throw AdapterException.nonRetryable("FX_CATEGORY_INVALID", "目标类目违规");
            case RETRYABLE -> throw AdapterException.retryable("429", "平台限流");
            case BUG -> throw new IllegalStateException("fixture 注入的非 AdapterException 缺陷");
        };
    }

    @Override
    public Optional<PlatformItemRef> reconcile(String listingId) {
        reconcileCalls.add(listingId);
        return reconcileScript.isEmpty() ? reconcileFallback : reconcileScript.remove(0);
    }

    public List<Listing> addCalls() {
        return List.copyOf(addCalls);
    }

    public List<String> reconcileCalls() {
        return List.copyOf(reconcileCalls);
    }
}
