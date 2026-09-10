package io.autocommerce.content.ai;

import io.autocommerce.core.step.ProviderException;
import io.autocommerce.core.step.StepExecutionException;
import io.autocommerce.core.step.StepOutcome;
import io.autocommerce.core.step.StepResult;
import io.autocommerce.content.model.ContentWorkingSet;
import io.autocommerce.content.step.ListingStepContext;
import io.autocommerce.content.testsupport.ContentDocs;
import io.autocommerce.content.testsupport.FakeLlmGateway;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * i18n.backfill（specs/0006 §8）：翻译回填 master canonical —— 一处翻译多处复用，已有译文不重翻。
 */
class I18nBackfillStepTest {

    private final FakeLlmGateway llm = new FakeLlmGateway();

    @Test
    void backfillsMissingTargetLocales_intoCanonical() throws Exception {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        I18nBackfillStep step = new I18nBackfillStep(llm);
        llm.respond("Wireless Bluetooth Earphones");

        StepResult result = step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(result.outcome()).isEqualTo(StepOutcome.OK);
        assertThat(working.spuTitles()).containsEntry("en", "Wireless Bluetooth Earphones")
                .containsEntry(ContentDocs.SOURCE_LOCALE, ContentDocs.SOURCE_TITLE);
        // 只翻缺的 locale：源 locale 不走 LLM
        assertThat(llm.requests()).hasSize(1);
        assertThat(llm.requests().get(0).model()).isNull();
    }

    @Test
    void skipsAlreadyTranslatedLocales_idempotent() throws Exception {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        working.spuTitles().put("en", "existing translation");
        I18nBackfillStep step = new I18nBackfillStep(llm);

        StepResult result = step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(result.outcome()).isEqualTo(StepOutcome.OK);
        assertThat(llm.requests()).isEmpty();
        assertThat(working.spuTitles()).containsEntry("en", "existing translation");
    }

    @Test
    void providerFailure_isHardFailure_notDegrade() {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        I18nBackfillStep step = new I18nBackfillStep(llm);
        llm.failWith(new ProviderException("endpoint down"));

        assertThatThrownBy(() -> step.execute(new ListingStepContext(working, step.descriptor().params())))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("翻译回填失败");
        // 硬依赖不写半成品
        assertThat(working.spuTitles()).doesNotContainKey("en");
    }

    @Test
    void missingSourceLocaleTitle_isHardFailure() {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        working.spuTitles().clear();
        I18nBackfillStep step = new I18nBackfillStep(llm);

        assertThatThrownBy(() -> step.execute(new ListingStepContext(working, step.descriptor().params())))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("缺来源 locale 标题");
    }

    @Test
    void descriptor_declaresFieldLevelContract() {
        var descriptor = new I18nBackfillStep(llm).descriptor();

        assertThat(descriptor.id()).isEqualTo("i18n.backfill");
        assertThat(descriptor.input()).extracting("path")
                .containsExactlyInAnyOrder("spu.titles", "spu.descriptions");
        assertThat(descriptor.output()).extracting("path")
                .containsExactlyInAnyOrder("spu.titles", "spu.descriptions");
        assertThat(descriptor.modelRequirement().name()).isEqualTo("LLM");
        assertThat(Map.of()).isEmpty();
    }
}
