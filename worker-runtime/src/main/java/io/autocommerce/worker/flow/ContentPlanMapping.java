package io.autocommerce.worker.flow;

import io.autocommerce.content.ContentPlan;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 链路类型 → {@link ContentPlan} 的映射（specs/0007 §4.3）——**编排层的配置，而非硬编码**。
 *
 * <p>默认映射（{@link #standard()}）：
 * <ul>
 *   <li>{@link ContentChainKind#DOMESTIC} → {@code ContentPlan.standard().without(i18n.backfill)}；</li>
 *   <li>{@link ContentChainKind#CROSS_BORDER} → {@code ContentPlan.standard()}。</li>
 * </ul>
 *
 * <p><b>可配置</b>：构造器接受任意 {@code kind → plan} 表（要求覆盖全部枚举值，缺失即早失败），
 * 由装配点注入 {@link ListingFlowWorkflowLauncher}。映射发生在**编排层客户端侧**（launcher），
 * 这样映射结果随 {@link ListingFlowWorkflowInput#plan()} 进入 workflow 载荷——workflow 重放时只认
 * 载荷里的 plan，不依赖任何外部可变量，确定性不受配置变更影响（Temporal workflow 实现必须无参构造，
 * 无法注入映射；把映射留在 launcher 是既保确定性又保可配置的落点）。
 */
public final class ContentPlanMapping {

    private final Map<ContentChainKind, ContentPlan> plans;

    /**
     * @param plans 完整的 {@code kind → plan} 表；必须覆盖 {@link ContentChainKind} 全部常量
     *              （缺一即 {@link IllegalArgumentException}，避免"漏配某类型"静默落空）
     */
    public ContentPlanMapping(Map<ContentChainKind, ContentPlan> plans) {
        Objects.requireNonNull(plans, "链路类型 → ContentPlan 映射表必填");
        EnumMap<ContentChainKind, ContentPlan> copy = new EnumMap<>(ContentChainKind.class);
        for (ContentChainKind kind : ContentChainKind.values()) {
            ContentPlan plan = plans.get(kind);
            if (plan == null) {
                throw new IllegalArgumentException("缺链路类型的 ContentPlan 映射: " + kind);
            }
            copy.put(kind, plan);
        }
        this.plans = Map.copyOf(copy);
    }

    /** specs/0007 §4.3 的默认映射（国内去 i18n、跨境保留）。 */
    public static ContentPlanMapping standard() {
        return new ContentPlanMapping(Map.of(
                ContentChainKind.DOMESTIC, ContentPlan.standard().without(ContentPlan.I18N_BACKFILL),
                ContentChainKind.CROSS_BORDER, ContentPlan.standard()));
    }

    /** 取某链路类型的执行计划。 */
    public ContentPlan planFor(ContentChainKind kind) {
        Objects.requireNonNull(kind, "链路类型（chain）必填");
        return plans.get(kind);
    }
}
