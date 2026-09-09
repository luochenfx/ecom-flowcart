package io.autocommerce.core.contract;

import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.contract.AdapterException;
import io.autocommerce.core.contract.dto.PlatformItemRef;
import io.autocommerce.core.contract.dto.PublishResult;

import java.util.Optional;

/**
 * 铺货能力（specs/0005 §2，消费方 = publish workflow #11）。add 消费就绪 Listing
 * （内容/类目/属性/规格映射/SKU 售价都是数据，Adapter 只消费不内置业务映射）。
 */
public interface PublishCapability extends Capability {

    /**
     * 发布 Listing 到平台 → platform_item_id/url（回填 execution_projection / Listing 资产）。
     * 只抛 {@link AdapterException}；AMBIGUOUS（超时不知是否生效）不得自动重发。
     */
    PublishResult add(Listing listing) throws AdapterException;

    /**
     * reconcile（可选能力，specs/0005 §8）：超时歧义时核实本次外部调用是否已生效
     * （平台是否已创建 Listing）。未实现 = Optional.empty()，走保守路径。
     *
     * @param listingId 我方 Listing id（确定性，对齐 workflowId 语义）
     */
    default Optional<PlatformItemRef> reconcile(String listingId) throws AdapterException {
        return Optional.empty();
    }
}
