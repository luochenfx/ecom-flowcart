package io.autocommerce.publish;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 一次铺货步骤的决策结果（activity ↔ workflow 载荷；{@link PublishDisposition} + 事实字段）。
 *
 * <p>{@code occurredAt} = 本次决策对应<b>已落库事实</b>的时间（PUBLISHED → {@code publishedAt}；
 * AMBIGUOUS → 记录时刻 {@code updatedAt}）——它是 {@code envelope.occurred_at} 的输入，
 * <b>不是</b>广播时刻；重放时复用已落库值，保证 {@code envelope.id} 幂等锚稳定。
 * 未落库的处置（{@link PublishDisposition#NEEDS_ADD} / {@link PublishDisposition#RETRYABLE}）为 null。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PublishDecision(
        PublishDisposition disposition,
        String listingId,
        String platformItemId,
        String platformItemUrl,
        String occurredAt,
        String reason) {

    /** 可重入检查未命中：需继续 add。 */
    public static PublishDecision needsAdd(String listingId) {
        return new PublishDecision(PublishDisposition.NEEDS_ADD, listingId, null, null, null, null);
    }

    /** 幂等命中：复用既有 PUBLISHED 事实（不重复 add，也不重发事件）。 */
    public static PublishDecision alreadyPublished(PublishState state) {
        return new PublishDecision(PublishDisposition.ALREADY_PUBLISHED, state.listingId(),
                state.platformItemId(), state.platformItemUrl(), state.publishedAt(), null);
    }

    /** 本次新落库 PUBLISHED 事实（add 成功 / reconcile 回填 / 人工确认回填）→ 广播 listing.published。 */
    public static PublishDecision published(PublishState state) {
        return new PublishDecision(PublishDisposition.PUBLISHED, state.listingId(),
                state.platformItemId(), state.platformItemUrl(), state.publishedAt(), null);
    }

    /** 超时歧义已落库 AMBIGUOUS → 广播 listing.ambiguous 并挂起。 */
    public static PublishDecision ambiguous(PublishState state) {
        return new PublishDecision(PublishDisposition.AMBIGUOUS, state.listingId(),
                null, null, state.updatedAt(), state.reason());
    }

    /** 业务拒绝已落库 REJECTED → workflow failed。 */
    public static PublishDecision rejected(PublishState state) {
        return new PublishDecision(PublishDisposition.REJECTED, state.listingId(),
                null, null, state.updatedAt(), state.reason());
    }

    /** 可重试耗尽 / 意外未知已落库 FAILED → workflow failed。 */
    public static PublishDecision failed(PublishState state) {
        return new PublishDecision(PublishDisposition.FAILED, state.listingId(),
                null, null, state.updatedAt(), state.reason());
    }

    /** 临时故障（未落库）→ activity 以可重试失败抛回，交 Temporal RetryPolicy。 */
    public static PublishDecision retryable(String listingId, String reason) {
        return new PublishDecision(PublishDisposition.RETRYABLE, listingId, null, null, null, reason);
    }

    /** 是否为"已发布"（幂等命中或本次新发布）——workflow 据此收敛为 completed。 */
    public boolean published() {
        return disposition == PublishDisposition.PUBLISHED
                || disposition == PublishDisposition.ALREADY_PUBLISHED;
    }

    /** 是否为幂等命中（复用既有事实，非本次新发布）。 */
    public boolean reused() {
        return disposition == PublishDisposition.ALREADY_PUBLISHED;
    }
}
