package io.autocommerce.worker.flow;

import io.autocommerce.content.ContentPlan;
import io.autocommerce.core.catalog.model.CategoryRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编排链坐标口径（specs/0007 §4.1）+ 链路类型 → ContentPlan 映射（§4.3，可配置）。
 */
class ListingFlowRuntimeTest {

    @Test
    void workflowId_isDeterministicFulfillmentForm() {
        assertThat(ListingFlowRuntime.workflowIdFor("spu-1", "shop-a"))
                .isEqualTo("fulfillment-spu-1-shop-a");
    }

    @Test
    void workflowIdRejectsBlankArguments() {
        assertThatThrownBy(() -> ListingFlowRuntime.workflowIdFor(null, "c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ListingFlowRuntime.workflowIdFor("  ", "c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ListingFlowRuntime.workflowIdFor("p", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void standardMapping_domesticOmitsI18n_crossBorderKeepsIt() {
        ContentPlanMapping mapping = ContentPlanMapping.standard();

        assertThat(mapping.planFor(ContentChainKind.DOMESTIC).stepIds())
                .as("国内链：关闭 i18n.backfill")
                .doesNotContain(ContentPlan.I18N_BACKFILL)
                .contains(ContentPlan.TITLE_REWRITE, ContentPlan.DESC_GENERATE,
                        ContentPlan.PRICE_STRATEGY, ContentPlan.MEDIA_PROCESS);
        assertThat(mapping.planFor(ContentChainKind.CROSS_BORDER).stepIds())
                .as("跨境链：保留 i18n.backfill（硬依赖）")
                .contains(ContentPlan.I18N_BACKFILL);
    }

    @Test
    void mappingIsConfigurable() {
        ContentPlan custom = new ContentPlan(List.of(new ContentPlan.PlanStep(ContentPlan.MEDIA_PROCESS, true)));
        ContentPlanMapping mapping = new ContentPlanMapping(Map.of(
                ContentChainKind.DOMESTIC, custom,
                ContentChainKind.CROSS_BORDER, ContentPlan.standard()));

        assertThat(mapping.planFor(ContentChainKind.DOMESTIC).stepIds())
                .containsExactly(ContentPlan.MEDIA_PROCESS);
    }

    @Test
    void mappingRejectsIncompleteTable() {
        assertThatThrownBy(() -> new ContentPlanMapping(Map.of(ContentChainKind.DOMESTIC, ContentPlan.standard())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CROSS_BORDER");
    }

    @Test
    void inputRequiresFieldsAndCopiesLocales() {
        List<String> locales = new java.util.ArrayList<>(List.of("zh-CN", "en"));
        ListingFlowWorkflowInput input = new ListingFlowWorkflowInput("spu-1", "shop-a",
                new CategoryRef("taobao", "5001", "数码/影音"), locales, ContentPlan.standard());
        locales.add("ja"); // 构造后改源集合不得影响不可变副本

        assertThat(input.locales()).containsExactly("zh-CN", "en");

        assertThatThrownBy(() -> new ListingFlowWorkflowInput(null, "c", new CategoryRef("t", "v", "l"),
                List.of("zh-CN"), ContentPlan.standard()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListingFlowWorkflowInput("s", "c", null,
                List.of("zh-CN"), ContentPlan.standard()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ListingFlowWorkflowInput("s", "c", new CategoryRef("t", "v", "l"),
                List.of(), ContentPlan.standard()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListingFlowWorkflowInput("s", "c", new CategoryRef("t", "v", "l"),
                List.of("zh-CN"), null))
                .isInstanceOf(NullPointerException.class);
    }
}
