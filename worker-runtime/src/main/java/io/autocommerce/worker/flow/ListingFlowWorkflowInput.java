package io.autocommerce.worker.flow;

import io.autocommerce.content.ContentPlan;
import io.autocommerce.core.catalog.model.CategoryRef;

import java.util.List;
import java.util.Objects;

/**
 * 编排链 workflow 输入（specs/0007 §4.2）：一路把「采集产物坐标 + 装配参数 + 内容计划」送进父 workflow。
 *
 * <p><b>{@code plan} 是请求方声明的链路类型经 {@link ContentPlanMapping} 映射后的结果</b>（映射在
 * {@link ListingFlowWorkflowLauncher} 里完成，见 {@link ContentPlanMapping} 类 javadoc）。输入携带
 * 已解析的 {@link ContentPlan} 而非链路类型本身，是为了让 workflow 自洽、确定性——重放只认载荷，不依赖
 * 任何外部映射表；编排层对 chain 的业务语义（国内 / 跨境）的认知止于 launcher 边界，workflow 只认计划。
 *
 * <p>{@code targetCategory}（目标平台叶子类目）与 {@code locales}（提交用语言集）由请求方携带
 * （specs/0002 §3 / specs/0007 §6.2），不在编排层推导。
 *
 * @param spuId          master 文档坐标（{@code CatalogStore} 键）
 * @param channelId      目标渠道账号 id
 * @param targetCategory 目标平台叶子类目（装配 Listing 的输入）
 * @param locales        提交用语言集（装配 Listing 的输入）
 * @param plan           内容链执行计划（由请求方声明的链路类型映射得到）
 */
public record ListingFlowWorkflowInput(String spuId, String channelId, CategoryRef targetCategory,
                                       List<String> locales, ContentPlan plan) {

    public ListingFlowWorkflowInput {
        if (spuId == null || spuId.isBlank()) {
            throw new IllegalArgumentException("spuId 必填");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId 必填");
        }
        Objects.requireNonNull(targetCategory, "目标平台叶子类目（targetCategory）必填");
        if (locales == null || locales.isEmpty()) {
            throw new IllegalArgumentException("locales 必填（提交用语言集）");
        }
        locales = List.copyOf(locales);
        Objects.requireNonNull(plan, "内容链计划必填（由链路类型映射，见 ContentPlanMapping）");
    }
}
