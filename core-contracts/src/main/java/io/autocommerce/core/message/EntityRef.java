package io.autocommerce.core.message;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 业务实体引用（schema: EntityRef）。type = listing/order/purchase_order/rma/spu/sku…；
 * id = 实体确定性 id（对 workflowId 业务键语义）。跨实体血缘靠逐跳 entity_ref，不强行共 correlation_id。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record EntityRef(String type, String id) {
}
