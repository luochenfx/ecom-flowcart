package io.autocommerce.publish;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Listing 铺货投影行（specs/0001 §8 字段语义；薄投影，<b>无状态机逻辑</b>——仅承载 workflow 在
 * 关键节点写入的事实字段）。
 *
 * <p>字段口径：
 * <ul>
 *   <li>{@code listingId} = Listing canonical id = 幂等锚（{@code biz_key}，对 workflowId 语义，见
 *       {@code PublishRuntime.workflowIdFor}）；</li>
 *   <li>{@code platformItemId} / {@code platformItemUrl} = add 成功回填的平台商品引用（订单回传关联键）；
 *       未发布 = null；</li>
 *   <li>{@code publishedAt} = PUBLISHED 事实时间（{@code occurred_at} 派生输入，<b>非广播时刻</b>）；</li>
 *   <li>{@code reason} = AMBIGUOUS / REJECTED / FAILED 的结构化原因（平台错误码 + 描述，
 *       对 specs/0001 §8 的 {@code terminal_reason}）；PUBLISHED = null；</li>
 *   <li>{@code updatedAt} = 本行最近一次写入的事实时间（AMBIGUOUS 的 {@code occurred_at} 取它）。</li>
 * </ul>
 *
 * <p>v1 薄投影刻意不含 {@code retry_count} / {@code last_error}：重试次数与逐次错误在 Temporal
 * Event History（activity 尝试记录）里，投影只反映"最新态 + 终态原因"——避免双写同一事实。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PublishState(
        String listingId,
        PublishStatus status,
        String platformItemId,
        String platformItemUrl,
        String publishedAt,
        String reason,
        String updatedAt) {
}
