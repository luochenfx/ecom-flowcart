package io.autocommerce.content.ai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.autocommerce.core.catalog.model.ListingSku;
import io.autocommerce.core.step.StepExecutionException;
import io.autocommerce.core.step.StepOutcome;
import io.autocommerce.core.step.StepResult;
import io.autocommerce.content.model.ContentWorkingSet;
import io.autocommerce.content.step.ListingStepContext;
import io.autocommerce.content.testsupport.ContentDocs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * price.strategy（specs/0006 §8）：RULE 纯规则加价 —— 成本价 × 加价率；配置非法时退默认加价率并降级。
 */
class PriceStrategyStepTest {

    private final PriceStrategyStep step = new PriceStrategyStep();

    @Test
    void appliesMarkupRate_onCostPrices() throws Exception {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());

        StepResult result = step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(result.outcome()).isEqualTo(StepOutcome.OK);
        assertThat(working.skuSet()).extracting("skuId")
                .containsExactly("sku-1688-6688990011-1", "sku-1688-6688990011-2");
        assertThat(working.skuSet()).extracting(s -> s.price().amount()).containsExactly("82.62", "93.60");
        assertThat(working.skuSet()).extracting(ListingSku::enabled).containsExactly(true, true);
    }

    @Test
    void preservesEnabledFlag_fromDraft() throws Exception {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        working.skuSet(List.of(
                new ListingSku("sku-1688-6688990011-1", new io.autocommerce.core.catalog.model.Money("45.90", "CNY"), true),
                new ListingSku("sku-1688-6688990011-2", new io.autocommerce.core.catalog.model.Money("52.00", "CNY"), false)));

        step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(working.skuSet()).extracting(ListingSku::enabled).containsExactly(true, false);
    }

    @Test
    void invalidMarkupRate_fallsBackToDefaultRate_andDegrades() throws Exception {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        var params = JsonNodeFactory.instance.objectNode();
        params.put("markup_rate", "abc");
        params.put("default_markup_rate", "2.00");

        StepResult result = step.execute(new ListingStepContext(working, params));

        assertThat(result.outcome()).isEqualTo(StepOutcome.DEGRADED);
        assertThat(result.reason()).contains("默认加价率");
        // 45.90 × 2.00 = 91.80
        assertThat(working.skuSet()).extracting(s -> s.price().amount()).containsExactly("91.80", "104.00");
    }

    @Test
    void bothRatesUnusable_isHardFailure_leavingDraftPrices() {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        var params = JsonNodeFactory.instance.objectNode();
        params.put("markup_rate", "0");
        params.put("default_markup_rate", "-1");

        assertThatThrownBy(() -> step.execute(new ListingStepContext(working, params)))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("加价率不可用");
        // 未写入：草稿价（= 成本价）保持不变（"产物缺省 = 上游值"）
        assertThat(working.skuSet()).extracting(s -> s.price().amount()).containsExactly("45.90", "52.00");
    }

    @Test
    void descriptor_isPureRule_zeroModel() {
        var descriptor = step.descriptor();

        assertThat(descriptor.id()).isEqualTo("price.strategy");
        assertThat(descriptor.modelRequirement().name()).isEqualTo("RULE");
        assertThat(descriptor.output()).extracting("path").containsExactly("listing.sku_set");
    }
}
