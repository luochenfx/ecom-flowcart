package io.autocommerce.content.ai;

import io.autocommerce.core.step.ProviderException;
import io.autocommerce.core.step.StepOutcome;
import io.autocommerce.core.step.StepResult;
import io.autocommerce.content.model.ContentWorkingSet;
import io.autocommerce.content.step.ListingStepContext;
import io.autocommerce.content.testsupport.ContentDocs;
import io.autocommerce.content.testsupport.FakeLlmGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * title.rewrite / desc.generate（specs/0006 §8）：改写稿落 Listing 字段；失败降级为"保留原文"
 * （不写 overrides = 铺货回退 canonical），登记 degraded。
 */
class RewriteStepsTest {

    private final FakeLlmGateway llm = new FakeLlmGateway();

    @Test
    void titleRewrite_writesOverridesPerLocale() throws Exception {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        working.spuTitles().put("en", "Wireless Earphones");
        TitleRewriteStep step = new TitleRewriteStep(llm);
        llm.respond("改写后的中文标题").respond("Rewritten English Title");

        StepResult result = step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(result.outcome()).isEqualTo(StepOutcome.OK);
        assertThat(working.titleOverrides())
                .containsEntry("zh-CN", "改写后的中文标题")
                .containsEntry("en", "Rewritten English Title");
    }

    @Test
    void titleRewrite_degradesOnProviderFailure_leavingOriginal() throws Exception {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        TitleRewriteStep step = new TitleRewriteStep(llm);
        llm.failWith(new ProviderException("timeout"));

        StepResult result = step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(result.outcome()).isEqualTo(StepOutcome.DEGRADED);
        assertThat(result.reason()).contains("标题改写失败");
        assertThat(working.titleOverrides()).isEmpty();
        assertThat(working.degradedSteps()).isEmpty();
    }

    @Test
    void titleRewrite_degradesWhenNoCanonicalTitle() throws Exception {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        working.spuTitles().clear();
        TitleRewriteStep step = new TitleRewriteStep(llm);

        StepResult result = step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(result.outcome()).isEqualTo(StepOutcome.DEGRADED);
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    void descGenerate_usesTitleAsMaterial_whenDescriptionMissing() throws Exception {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        DescGenerateStep step = new DescGenerateStep(llm);
        llm.respond("生成的描述文案");

        StepResult result = step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(result.outcome()).isEqualTo(StepOutcome.OK);
        assertThat(working.descriptionOverrides()).containsEntry("zh-CN", "生成的描述文案");
    }

    @Test
    void descGenerate_degradesOnProviderFailure() throws Exception {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        DescGenerateStep step = new DescGenerateStep(llm);
        llm.failWith(new ProviderException("boom"));

        StepResult result = step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(result.outcome()).isEqualTo(StepOutcome.DEGRADED);
        assertThat(working.descriptionOverrides()).isEmpty();
    }

    @Test
    void descriptors_declareRewriteOutputsOnListing() {
        var title = new TitleRewriteStep(llm).descriptor();
        var desc = new DescGenerateStep(llm).descriptor();

        assertThat(title.id()).isEqualTo("title.rewrite");
        assertThat(title.output()).extracting("path").containsExactly("listing.title_overrides");
        assertThat(title.modelRequirement().name()).isEqualTo("LLM");

        assertThat(desc.id()).isEqualTo("desc.generate");
        assertThat(desc.output()).extracting("path").containsExactly("listing.description_overrides");
        assertThat(desc.modelRequirement().name()).isEqualTo("LLM");
    }
}
