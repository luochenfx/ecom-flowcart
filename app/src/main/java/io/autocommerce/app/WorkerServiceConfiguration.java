package io.autocommerce.app;

import io.autocommerce.core.contract.AddressCapability;
import io.autocommerce.core.contract.PublishCapability;
import io.autocommerce.core.contract.PurchaseCapability;
import io.autocommerce.core.contract.RmaCapability;
import io.autocommerce.core.contract.ShipmentCapability;
import io.autocommerce.order.address.AddressCipher;
import io.autocommerce.order.purchase.PurchaseFulfillmentService;
import io.autocommerce.order.purchase.PurchasePlanner;
import io.autocommerce.order.purchase.SourcingRefResolver;
import io.autocommerce.order.rma.RmaSyncService;
import io.autocommerce.order.store.JsonFileOrderStore;
import io.autocommerce.order.store.OrderStore;
import io.autocommerce.publish.JsonFilePublishStateStore;
import io.autocommerce.publish.PublishService;
import io.autocommerce.publish.PublishStateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/**
 * worker 侧业务服务装配（specs/0007 §7.2）：为各链 worker 的 activity 提供域服务依赖。
 *
 * <p>仅当 {@code app.role} 含 {@code worker} 时装配（{@link WorkerRoleCondition}）——纯 {@code api}
 * 角色部署无需这些服务（REST 入口只用 launcher + 采集服务）。
 *
 * <h2>v1 存储形态</h2>
 * {@code OrderStore} / {@code PublishStateStore} 保持 JSON 文件实现（specs/0007 §8.5 本次不动），
 * 落 {@code app.data-root}；只有 catalog 落 Postgres（{@link PersistenceConfiguration}）。
 *
 * <h2>诚实登记的装配缺口</h2>
 * <ul>
 *   <li>{@link SourcingRefResolver#none()}：货源解析端口（销售订单行 → 1688 供应商 / offer 坐标）
 *       v1 以"无货源"占位（其 javadoc 明定），故本装配下的订单链不会进入采购下单——真实
 *       catalog-backed 解析器属后续（不在 #74 范围）。</li>
 *   <li>{@link PublishCapability} / {@link PurchaseCapability} / {@link ShipmentCapability} /
 *       {@link AddressCapability} / {@link RmaCapability} 经惰性代理注入：v1 生产侧未必有对应平台
 *       Adapter（见 {@link CapabilityConfiguration}），缺实现时在**调用期**显式报错，不阻断装配。</li>
 * </ul>
 */
@Configuration
@Conditional(WorkerRoleCondition.class)
public class WorkerServiceConfiguration {

    /** 统一时间源（活动 / 服务落库时间）。 */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** 铺货状态文档库（v1 JSON 文件实现，specs/0007 §8.5）。 */
    @Bean
    PublishStateStore publishStateStore(AppProperties properties) {
        return new JsonFilePublishStateStore(Path.of(properties.getDataRoot()));
    }

    /** 订单文档库（v1 JSON 文件实现，specs/0007 §8.5）。 */
    @Bean
    OrderStore orderStore(AppProperties properties) {
        return new JsonFileOrderStore(Path.of(properties.getDataRoot()));
    }

    /** 地址明文加密（AES-256-GCM；密钥来自 {@code app.address-key}，生产由 env 覆盖）。 */
    @Bean
    AddressCipher addressCipher(AppProperties properties) {
        return AddressCipher.fromSecret(properties.getAddressKey());
    }

    /** 拆采购单规划器（v1 货源解析端口占位，见类 javadoc）。 */
    @Bean
    PurchasePlanner purchasePlanner() {
        return new PurchasePlanner(SourcingRefResolver.none());
    }

    /** 采购履约服务（拆单 / 下单 / 发货回传）。 */
    @Bean
    PurchaseFulfillmentService purchaseFulfillmentService(OrderStore store, PurchaseCapability purchase,
                                                          ShipmentCapability shipment, AddressCapability address,
                                                          AddressCipher cipher, PurchasePlanner planner,
                                                          Clock clock) {
        return new PurchaseFulfillmentService(store, purchase, shipment, address, cipher, planner, clock);
    }

    /** 售后状态同步服务。 */
    @Bean
    RmaSyncService rmaSyncService(OrderStore store, RmaCapability rma, Clock clock) {
        return new RmaSyncService(store, rma, clock);
    }

    /** 铺货域服务（状态机 + 落库 + 外部 PublishCapability 调用）。 */
    @Bean
    PublishService publishService(PublishStateStore store, PublishCapability publish, Clock clock) {
        return new PublishService(store, publish, clock);
    }
}
