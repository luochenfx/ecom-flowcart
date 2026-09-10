package io.autocommerce.worker.content;

import io.autocommerce.content.ContentStepRun;
import io.autocommerce.core.catalog.model.DegradedStep;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容链 workflow 结果（#20 AC-1"收敛为内容就绪态"）。
 *
 * <p><b>contentReady 是推导量而非字段</b>：workflow 只在全部步骤跑完（每步 OK / DEGRADED / 未在
 * 计划内）时才返回本记录；硬依赖失败走 workflow failed（无返回）。所以"能拿到结果"本身就等于
 * "内容就绪"——如果再加一个{@code contentReady()} 方法（或字段），只是再造一个恒为 true 的占位
 * 字段，会制造第二个真相源（一旦两源不同步，立刻失语）。{@code degradedSteps} 非空仍算就绪
 * （HITL 复核，不阻断铺货，specs/0006 §2）。
 *
 * @param spuId          文档坐标
 * @param listingId      目标 Listing
 * @param runs           逐 Step 执行记录（顺序同 plan；含模型与 token 用量，看板聚合用）
 * @param degradedSteps  降级留痕（与已落库 Listing 的 degraded_steps 一致，调用方免回读）
 */
public record ContentWorkflowResult(String spuId, String listingId, List<ContentStepRun> runs,
                                    List<DegradedStep> degradedSteps) {

    public ContentWorkflowResult {
        runs = runs == null ? List.of() : List.copyOf(runs);
        degradedSteps = degradedSteps == null ? List.of() : List.copyOf(degradedSteps);
    }

    /** 降级 Step 的 id 清单（看板 / demo 断言用）。 */
    public List<String> degradedStepIds() {
        List<String> ids = new ArrayList<>();
        for (DegradedStep step : degradedSteps) {
            ids.add(step.step());
        }
        return List.copyOf(ids);
    }
}
