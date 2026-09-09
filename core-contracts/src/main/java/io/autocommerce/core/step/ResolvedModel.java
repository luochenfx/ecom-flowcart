package io.autocommerce.core.step;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 模型解析结果（specs/0006 §4 ResolvedModel）。v1 = 静态配置映射；未来编排器在同一 seam 路由。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ResolvedModel(ProviderId providerId, String model) {
}
