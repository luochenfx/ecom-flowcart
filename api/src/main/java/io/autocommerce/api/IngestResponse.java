package io.autocommerce.api;

import java.util.List;

/**
 * {@code POST /api/v1/ingest} 成功响应（HTTP 202，specs/0007 §6.2）。载荷命名 = snake_case（全局策略，
 * 见 {@link ApiJacksonConfiguration}）。
 *
 * <p>{@code sku_ids} / {@code media_ids} 取自**同步**采集的 {@code IngestResult}（采集已完成，
 * 立即可知）；{@code listing_id} 与 {@code flow_workflow_id} 是后续追踪坐标，各自复用单一事实源推导：
 * <ul>
 *   <li>{@code listing_id} = {@code PublishRuntime.workflowIdFor(spuId, channelId)}（{@code listing-{spuId}-{channelId}}）；</li>
 *   <li>{@code flow_workflow_id} = {@code ListingFlowRuntime.workflowIdFor(spuId, channelId)}（{@code fulfillment-{spuId}-{channelId}}）。</li>
 * </ul>
 */
public record IngestResponse(
        String spuId,
        String listingId,
        String flowWorkflowId,
        List<String> skuIds,
        List<String> mediaIds) {
}
