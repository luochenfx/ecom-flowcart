package io.autocommerce.adapter.ali1688;

import io.autocommerce.core.contract.AuthCapability;
import io.autocommerce.core.contract.OfferFetchCapability;
import io.autocommerce.core.contract.PurchaseCapability;
import io.autocommerce.core.contract.ShipmentCapability;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPI 装配入口：能力清单 = {OfferFetch, Purchase, Auth}；<b>ShipmentCapability 不实现</b>
 * （销售平台侧专属，见 ADR-0007 归属侧别纠偏）——取未实现能力按契约抛
 * {@link IllegalArgumentException}。
 */
class Ali1688AdapterProviderTest {

    private final Ali1688AdapterProvider provider = new Ali1688AdapterProvider();

    @Test
    void declaresSourceSideCapabilitiesOnly() {
        assertThat(provider.platform()).isEqualTo("1688");
        assertThat(provider.capabilities()).containsExactlyInAnyOrder(OfferFetchCapability.class,
                PurchaseCapability.class, AuthCapability.class);
        assertThat(provider.capabilities()).doesNotContain(ShipmentCapability.class);
    }

    @Test
    void resolvesImplementedCapabilities() {
        assertThat(provider.getCapability(OfferFetchCapability.class))
                .isInstanceOf(Ali1688OfferFetch.class);
        assertThat(provider.getCapability(PurchaseCapability.class))
                .isInstanceOf(Ali1688Purchase.class);
        assertThat(provider.getCapability(AuthCapability.class)).isInstanceOf(Ali1688Auth.class);
    }

    @Test
    void shipmentIsNotImplementedOnSourceSideAdapter() {
        assertThatThrownBy(() -> provider.getCapability(ShipmentCapability.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adapter-1688 未实现能力");
    }
}
