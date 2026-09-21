package io.autocommerce.worker.flow;

import io.autocommerce.core.catalog.model.DegradedStep;

import java.util.List;

/**
 * 编排链执行结果（workflow completed 时返回）。
 *
 * <p>父链在子链全部收敛后才返回本记录。两条子链的失败语义已在编排层体现：内容硬依赖失败 / 内容就绪
 * 断言失败 ⇒ 父 workflow <b>failed</b>（无返回）；铺货 {@code AMBIGUOUS} ⇒ 父链<b>同步挂起</b>（无返回，
 * 直到 signal 裁定）。故只要能拿到本结果，即代表链路已收敛且已铺货。
 *
 * @param spuId           文档坐标
 * @param listingId       目标 Listing（= {@code listing-{spuId}-{channelId}}）
 * @param published       铺货是否收敛（completed 恒为 true；留字段以便 API 层统一呈现）
 * @param platformItemId  平台商品级 id
 * @param platformItemUrl 平台商品链接（可空）
 * @param contentReady    内容是否就绪（本编排链以"降级即失败"保证；completed 恒为 true）
 * @param degradedSteps   内容链降级留痕（正常收敛时为空——非空不会走到本结果）
 * @param reason          失败 / 挂起原因（正常收敛时为空）
 */
public record ListingFlowWorkflowResult(String spuId, String listingId, boolean published,
                                        String platformItemId, String platformItemUrl,
                                        boolean contentReady, List<DegradedStep> degradedSteps,
                                        String reason) {

    public ListingFlowWorkflowResult {
        degradedSteps = degradedSteps == null ? List.of() : List.copyOf(degradedSteps);
    }
}
