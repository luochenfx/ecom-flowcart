package io.autocommerce.content;

import io.autocommerce.core.catalog.model.DegradedStep;

import java.util.List;

/**
 * 内容链一次执行的结果（#20 AC-1"收敛为内容就绪态"）。
 *
 * <p><b>contentReady 是推导量而非持久化字段</b>：本类由 content 模块在进程内顺序编排（测试 / demo /
 * 非 Temporal 调用方）路径下使用，{@code contentReady} 字段是表达式的便利出口（恒
 * {@code true}——因为本类只在该次执行走完才被构造；硬依赖失败由执行器抛
 * {@code ContentChainFailedException}，不进本类）。含义与 {@code ContentWorkflowResult} 的
 * "能拿到结果 = 内容就绪" 同源（specs/0006 §10 #20 拍板的正式决议，避免两个真相源）。
 *
 * @param listingId     目标 Listing
 * @param contentReady  是否收敛为内容就绪态：链路跑完（每个 Step 得到 OK / DEGRADED / 未在计划内）。
 *                      specs/0006 §2：内容就绪 = {@code provenance.step=AI/HUMAN}，
 *                      {@code degradedSteps} 非空仍算就绪（HITL 复核，不阻断铺货）
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
