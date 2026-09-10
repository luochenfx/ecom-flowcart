package io.autocommerce.content;

import io.autocommerce.core.catalog.model.DegradedStep;

import java.util.List;

/**
 * 内容链一次执行的结果（#20 AC-1"收敛为内容就绪态"）。
 *
 * @param listingId     目标 Listing
 * @param contentReady  是否收敛为内容就绪态：链路跑完（每个 Step 得到 OK / DEGRADED / 未在计划内）。
 *                      specs/0006 §2：内容就绪 = {@code provenance.step=AI/HUMAN}，
 *                      {@code degraded_steps} 非空仍算就绪（HITL 复核，不阻断铺货）
 * @param runs          逐 Step 执行记录（顺序同 plan）
 * @param degradedSteps 降级留痕（与写入 Listing 的一致，便于调用方免回读）
 */
public record ContentChainResult(String listingId, boolean contentReady, List<ContentStepRun> runs,
                                 List<DegradedStep> degradedSteps) {

    public ContentChainResult {
        runs = runs == null ? List.of() : List.copyOf(runs);
        degradedSteps = degradedSteps == null ? List.of() : List.copyOf(degradedSteps);
    }
}
