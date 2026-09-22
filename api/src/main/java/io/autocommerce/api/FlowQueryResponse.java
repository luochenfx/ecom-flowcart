package io.autocommerce.api;

import io.autocommerce.core.catalog.model.DegradedStep;
import io.autocommerce.worker.flow.ListingFlowWorkflowResult;

import java.util.List;

/**
 * {@code GET /api/v1/flows/{workflowId}} 响应（specs/0007 §6.1）。载荷命名 = snake_case（全局策略，
 * 见 {@link ApiJacksonConfiguration}）。
 *
 * <h2>status 与「失败」可区分（specs/0007 §4.5）</h2>
 * 编排链可能长期挂起等裁定（铺货停在 {@code AMBIGUOUS}），此时 Temporal 侧就是 {@code RUNNING}。
 * <b>{@code RUNNING} 是合法响应，不等于出错</b>——{@code status} 显式承载 Temporal execution 状态
 * （{@code RUNNING} / {@code COMPLETED} / {@code FAILED} / {@code CANCELED} / ...），
 * 与 {@code FAILED} 在响应中天然可区分、不可混同。
 *
 * <h2>结果字段</h2>
 * 编排链成功收敛（{@code COMPLETED}）时才取得到 {@link ListingFlowWorkflowResult}，故 {@code spu_id} …
 * {@code degraded_steps} 仅在 {@code COMPLETED} 时非空；{@code reason} 仅在 {@code FAILED} 时承载失败
 * 摘要，其余为 {@code null}。注意 {@code ListingFlowWorkflowResult.reason} 在 v1 恒为 null（其 javadoc：
 * API 层不应据其做非空判断），本响应的 {@code reason} 与之无关，专指**执行失败**的摘要。
 *
 * <p>{@code reason} 是**归一化**后的稳定文案（固定分类前缀 + 顶层异常类型名，见
 * {@code FlowQueryService#failureReason}），**不原样透传**底层异常 message——避免泄漏内部细节 / 长度失控。
 */
public record FlowQueryResponse(
        String workflowId,
        String status,
        String spuId,
        String listingId,
        Boolean published,
        String platformItemId,
        String platformItemUrl,
        Boolean contentReady,
        List<DegradedStep> degradedSteps,
        String reason) {

    /** {@code RUNNING}（含长期挂起）：无结果、无失败摘要。 */
    static FlowQueryResponse running(String workflowId) {
        return new FlowQueryResponse(workflowId, "RUNNING",
                null, null, null, null, null, null, null, null);
    }

    /** {@code COMPLETED}：填齐编排链结果。 */
    static FlowQueryResponse completed(String workflowId, ListingFlowWorkflowResult result) {
        return new FlowQueryResponse(
                workflowId,
                "COMPLETED",
                result.spuId(),
                result.listingId(),
                result.published(),
                result.platformItemId(),
                result.platformItemUrl(),
                result.contentReady(),
                result.degradedSteps(),
                null);
    }

    /** {@code FAILED}：填失败摘要（reason），无结果。 */
    static FlowQueryResponse failed(String workflowId, String reason) {
        return new FlowQueryResponse(workflowId, "FAILED",
                null, null, null, null, null, null, null, reason);
    }

    /** 其它终态（CANCELED / TERMINATED / TIMED_OUT / CONTINUED_AS_NEW）：只回状态，不取结果。 */
    static FlowQueryResponse terminal(String workflowId, String status) {
        return new FlowQueryResponse(workflowId, status,
                null, null, null, null, null, null, null, null);
    }
}
