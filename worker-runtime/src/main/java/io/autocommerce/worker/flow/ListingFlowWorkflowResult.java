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
 * <h2>v1 取值口径（Review R1 意见 2）</h2>
 * 唯一构造点是 {@code ListingFlowWorkflowImpl} 的成功返回处（specs/0007 §4.2）。失败走 {@code throw}、
 * 挂起不返回，故本记录只在成功路径被构造，四个"状态位"字段取值<b>恒定</b>：
 * <ul>
 *   <li>{@code published} 恒 {@code true}——拿到了结果就说明铺货已收敛（失败/挂起无返回）；</li>
 *   <li>{@code contentReady} 恒 {@code true}——内容就绪是铺货硬前置，未就绪已 throw；</li>
 *   <li>{@code degradedSteps} 恒空——非空即 throw，走不到本结果；</li>
 *   <li>{@code reason} 恒 {@code null}——本字段对应 specs/0007 §4.2 里"失败 / 挂起原因"的口径，但失败走
 *       throw、挂起不返回结果，<b>结构上取不到值</b>。此字段为 §4.2 口径保留，待 {@code api} 层
 *       （specs/0007 §10 步 6 / issue #73 REST）按需启用；当前 API 层不应据其做非空判断。</li>
 * </ul>
 * 保留这四个字段（而非删除）是为了让 API 层能统一呈现链路状态、并为后续版本（如主动上报 reason）
 * 留出稳定载荷形状。
 *
 * @param spuId           文档坐标
 * @param listingId       目标 Listing（= {@code listing-{spuId}-{channelId}}）
 * @param published       铺货是否收敛（v1 completed 恒为 true；留字段以便 API 层统一呈现）
 * @param platformItemId  平台商品级 id
 * @param platformItemUrl 平台商品链接（可空）
 * @param contentReady    内容是否就绪（本编排链以"降级即失败"保证；v1 completed 恒为 true）
 * @param degradedSteps   内容链降级留痕（v1 恒为空——非空不会走到本结果）
 * @param reason          失败 / 挂起原因（v1 恒为 null；为 specs/0007 §4.2 口径保留，见上）
 */
public record ListingFlowWorkflowResult(String spuId, String listingId, boolean published,
                                        String platformItemId, String platformItemUrl,
                                        boolean contentReady, List<DegradedStep> degradedSteps,
                                        String reason) {

    public ListingFlowWorkflowResult {
        degradedSteps = degradedSteps == null ? List.of() : List.copyOf(degradedSteps);
    }
}
