package io.autocommerce.adapter.fake;

import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.contract.AdapterErrorKind;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.AuthCapability;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.PlatformAdapterProvider;
import io.autocommerce.core.contract.PublishCapability;
import io.autocommerce.core.contract.PurchaseCapability;
import io.autocommerce.core.contract.ShipmentCapability;
import io.autocommerce.core.contract.dto.PublishResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 测试 Adapter 单测（specs/0007 §9.1 / §9 验收表第 3 行）：
 * <ul>
 *   <li>SPI 可被发现（{@link ServiceLoader} 经 META-INF/services 装配，platform = {@code fake-sales}）；</li>
 *   <li>能力清单 = {@code {PublishCapability}}（销售侧），与 adapter-1688 对称且互斥；取未实现能力抛
 *       {@link IllegalArgumentException}；</li>
 *   <li>四种处置（PUBLISHED / AMBIGUOUS / REJECTED / RETRYABLE）可分别触发。</li>
 * </ul>
 */
class FakeSalesAdapterProviderTest {

    private final FakeSalesAdapterProvider provider = new FakeSalesAdapterProvider();

    // ---- SPI 可被发现 ----

    @Test
    void isDiscoverableViaServiceLoader() {
        ServiceLoader<PlatformAdapterProvider> loader =
                ServiceLoader.load(PlatformAdapterProvider.class);
        assertThat(loader)
                .anySatisfy(p -> assertThat(p).isInstanceOf(FakeSalesAdapterProvider.class))
                .anySatisfy(p -> assertThat(p.platform()).isEqualTo("fake-sales"));
    }

    @Test
    void serviceLoaderDiscoveredProviderResolvesPublishCapability() {
        PlatformAdapterProvider discovered = ServiceLoader.load(PlatformAdapterProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p instanceof FakeSalesAdapterProvider)
                .findFirst()
                .orElseThrow();
        assertThat(discovered.getCapability(PublishCapability.class))
                .isInstanceOf(FakeSalesPublish.class);
    }

    // ---- 能力清单：销售侧 {PublishCapability}，与 adapter-1688 对称互斥 ----

    @Test
    void declaresSalesSideCapabilityOnly() {
        assertThat(provider.platform()).isEqualTo("fake-sales");
        assertThat(provider.capabilities()).containsExactly(PublishCapability.class);
        // 货源侧能力（1688 的 {OfferFetch, Purchase, Auth}）与 Shipment 均不实现（对称且互斥）
        assertThat(provider.capabilities()).doesNotContain(OfferFetchCapability.class,
                PurchaseCapability.class, AuthCapability.class, ShipmentCapability.class);
    }

    @Test
    void resolvesPublishCapability() {
        assertThat(provider.getCapability(PublishCapability.class)).isInstanceOf(FakeSalesPublish.class);
    }

    @Test
    void unimplementedCapabilityThrowsIllegalArgument() {
        assertThatThrownBy(() -> provider.getCapability(OfferFetchCapability.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adapter-fake 未实现能力");
        assertThatThrownBy(() -> provider.getCapability(ShipmentCapability.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adapter-fake 未实现能力");
    }

    // ---- 四种处置可分别触发 ----

    @Test
    void publishedDispositionReturnsPublishResult() {
        FakeSalesPublish publish = new FakeSalesPublish().platformItem("fake-9", "https://fake/9");

        PublishResult result = publish.add(listing("L-1"));

        assertThat(result.listingId()).isEqualTo("L-1");
        assertThat(result.platformItemId()).isEqualTo("fake-9");
        assertThat(result.url()).isEqualTo("https://fake/9");
        assertThat(publish.addCalls()).hasSize(1);
    }

    @Test
    void ambiguousDispositionThrowsAmbiguous() {
        FakeSalesPublish publish = new FakeSalesPublish()
                .addFallback(FakeSalesPublish.Disposition.AMBIGUOUS);

        assertThatThrownBy(() -> publish.add(listing("L-1")))
                .isInstanceOf(AdapterException.class)
                .extracting(e -> ((AdapterException) e).kind())
                .isEqualTo(AdapterErrorKind.AMBIGUOUS);
    }

    @Test
    void rejectedDispositionThrowsNonRetryable() {
        FakeSalesPublish publish = new FakeSalesPublish()
                .addFallback(FakeSalesPublish.Disposition.REJECTED);

        assertThatThrownBy(() -> publish.add(listing("L-1")))
                .isInstanceOf(AdapterException.class)
                .extracting(e -> ((AdapterException) e).kind())
                .isEqualTo(AdapterErrorKind.NON_RETRYABLE);
    }

    @Test
    void retryableDispositionThrowsRetryable() {
        FakeSalesPublish publish = new FakeSalesPublish()
                .addFallback(FakeSalesPublish.Disposition.RETRYABLE);

        assertThatThrownBy(() -> publish.add(listing("L-1")))
                .isInstanceOf(AdapterException.class)
                .extracting(e -> ((AdapterException) e).kind())
                .isEqualTo(AdapterErrorKind.RETRYABLE);
    }

    @Test
    void retryableDispositionCarriesRetryAfter() {
        Duration window = Duration.ofSeconds(30);
        FakeSalesPublish publish = new FakeSalesPublish()
                .addFallback(FakeSalesPublish.Disposition.RETRYABLE)
                .retryableAfter(window);

        assertThatThrownBy(() -> publish.add(listing("L-1")))
                .isInstanceOf(AdapterException.class)
                .extracting(e -> ((AdapterException) e).retryableAfter())
                .isEqualTo(window);
    }

    // ---- 脚本化：按序消费四态，用尽后回落 ----

    @Test
    void scriptedDispositionsAreConsumedInOrder() {
        FakeSalesPublish publish = new FakeSalesPublish()
                .script(FakeSalesPublish.Disposition.PUBLISHED,
                        FakeSalesPublish.Disposition.AMBIGUOUS,
                        FakeSalesPublish.Disposition.REJECTED,
                        FakeSalesPublish.Disposition.RETRYABLE)
                .addFallback(FakeSalesPublish.Disposition.PUBLISHED);

        assertThat(publish.add(listing("L-1")).platformItemId()).isEqualTo("fake-item-1");
        assertThat(kindOf(() -> publish.add(listing("L-2")))).isEqualTo(AdapterErrorKind.AMBIGUOUS);
        assertThat(kindOf(() -> publish.add(listing("L-3")))).isEqualTo(AdapterErrorKind.NON_RETRYABLE);
        assertThat(kindOf(() -> publish.add(listing("L-4")))).isEqualTo(AdapterErrorKind.RETRYABLE);
        // 脚本用尽 → 回落 PUBLISHED
        assertThat(publish.add(listing("L-5")).listingId()).isEqualTo("L-5");
        assertThat(publish.addCalls()).hasSize(5);
    }

    private static AdapterErrorKind kindOf(ThrowingCall call) {
        try {
            call.run();
            throw new AssertionError("预期抛 AdapterException 但正常返回");
        } catch (AdapterException e) {
            return e.kind();
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    private static Listing listing(String listingId) {
        return new Listing(listingId, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
