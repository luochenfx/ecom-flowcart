package io.autocommerce.adapterhost;

import io.autocommerce.core.contract.AuthCapability;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.PublishCapability;
import io.autocommerce.core.contract.PurchaseCapability;
import io.autocommerce.core.contract.ShipmentCapability;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link AdapterHost} 单测（specs/0007 §7.1 / #71 AC）。
 *
 * <p>本测试在 test classpath 上同时引入 {@code adapter-1688} 与 {@code adapter-fake}（均 **test
 * scope**）——两者都经真实 SPI（{@code META-INF/services}）声明，故验证的是"同一 AdapterHost 能
 * 同时装配两个平台"，而不是依赖注入伪造。AdapterHost 自身不依赖它们（main 依赖只有 core-contracts）。
 */
class AdapterHostTest {

    private final AdapterHost host = AdapterHost.load();

    // ---- 汇总：同时发现 1688 与 fake-sales 两个平台 ----

    @Test
    void discoversBothPlatforms() {
        assertThat(host.platforms()).contains("1688", "fake-sales");
    }

    @Test
    void platformsIsUnmodifiable() {
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> host.platforms().add("taobao"));
    }

    // ---- 按平台取能力：各自能力可取出，且类型正确 ----

    @Test
    void resolvesAli1688Capabilities() {
        assertThat(host.capability("1688", OfferFetchCapability.class)).isNotNull();
        assertThat(host.capability("1688", PurchaseCapability.class)).isNotNull();
        assertThat(host.capability("1688", AuthCapability.class)).isNotNull();
    }

    @Test
    void resolvesFakeSalesPublishCapability() {
        assertThat(host.capability("fake-sales", PublishCapability.class)).isNotNull();
    }

    @Test
    void exposesCapabilitySetsPerPlatform() {
        assertThat(host.capabilities("1688"))
                .containsExactlyInAnyOrder(OfferFetchCapability.class, PurchaseCapability.class,
                        AuthCapability.class);
        assertThat(host.capabilities("fake-sales")).containsExactly(PublishCapability.class);
    }

    @Test
    void ali1688DoesNotDeclareSalesSideShipmentCapability() {
        // 与 agent 事实一致：1688 是货源侧，不实现销售侧 ShipmentCapability
        assertThat(host.capabilities("1688")).doesNotContain(ShipmentCapability.class);
    }

    // ---- 明确错误：不静默返回 null ----

    @Test
    void unknownPlatformThrows() {
        assertThatExceptionOfType(UnknownPlatformException.class)
                .isThrownBy(() -> host.capability("taobao", OfferFetchCapability.class))
                .withMessageContaining("taobao");
    }

    @Test
    void unknownPlatformThrowsFromCapabilities() {
        assertThatExceptionOfType(UnknownPlatformException.class)
                .isThrownBy(() -> host.capabilities("no-such-platform"));
    }

    @Test
    void capabilityNotImplementedByPlatformThrows() {
        // fake-sales 只实现 PublishCapability；请求货源侧 OfferFetchCapability 应明确失败
        assertThatExceptionOfType(UnsupportedCapabilityException.class)
                .isThrownBy(() -> host.capability("fake-sales", OfferFetchCapability.class))
                .withMessageContaining("fake-sales");
    }

    @Test
    void capabilityNotImplementedByAli1688Throws() {
        // 1688 不实现销售侧 PublishCapability → 明确失败（不静默返回 null）
        assertThatExceptionOfType(UnsupportedCapabilityException.class)
                .isThrownBy(() -> host.capability("1688", PublishCapability.class));
    }

    // ---- load() 幂等：两次装配结果一致（无中央注册表，classpath 决定） ----

    @Test
    void loadIsRepeatable() {
        AdapterHost again = AdapterHost.load();
        assertThat(again.platforms()).isEqualTo(host.platforms());
    }
}
