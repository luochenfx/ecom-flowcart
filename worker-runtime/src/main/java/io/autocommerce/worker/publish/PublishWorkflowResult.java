package io.autocommerce.worker.publish;

/**
 * 铺货链执行结果（workflow completed 时返回）。
 *
 * @param listingId       Listing id（= workflowId）
 * @param platformItemId  平台商品级 id（add 成功 / reconcile 回填 / 人工确认回填）
 * @param platformItemUrl 平台商品链接（可空）
 * @param publishedAt     PUBLISHED 事实时间（已落库，非广播时刻）
 * @param reused          是否幂等命中（复用既有 PUBLISHED 事实，未本次新发布）
 */
public record PublishWorkflowResult(String listingId, String platformItemId, String platformItemUrl,
                                    String publishedAt, boolean reused) {
}
