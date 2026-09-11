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

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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
                .containsExactlyInAnyOrder("spu.titles", "spu.descriptions", "listing.locales");
        assertThat(descriptor.output()).extracting("path")
                .containsExactlyInAnyOrder("spu.titles", "spu.descriptions");
        assertThat(descriptor.modelRequirement().name()).isEqualTo("LLM");
        assertThat(Map.of()).isEmpty();
    }

    /**
     * AC-3：Listing 装配时声明的 {@code listing.locales} 优先于 params.target_locales（specs/0006 §2
     * "一处翻译多处复用"）。Listing 同时铺到 en + ru + es → 三个目标 locale 都要回填 master canonical。
     */
    @Test
    void listingLocalesOverrideParams_targetsForBackfill() throws Exception {
        var document = ContentDocs.masterWithListing(List.of("zh-CN", "en", "ru", "es"));
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        I18nBackfillStep step = new I18nBackfillStep(llm);
        llm.respond("translated-en").respond("translated-ru").respond("translated-es");

        StepResult result = step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(result.outcome()).isEqualTo(StepOutcome.OK);
        // 三个非源 locale 都被翻译回填到 master canonical
        assertThat(working.spuTitles()).containsEntry("en", "translated-en")
                .containsEntry("ru", "translated-ru")
                .containsEntry("es", "translated-es");
        assertThat(llm.requests()).hasSize(3);
    }

    /**
     * Hard cap = 10（specs/0006 §10 回填）：超过 10 个目标 locale → 截断 + 返回 DEGRADED。
     *
     * <p>本用例只钉 Step 侧事实：超限 locale 不翻译、cap 内的译文写进 master canonical、结果自报
     * "产物不完整"。**是否致命不由 Step 决定**——i18n.backfill 在标准计划里 {@code critical=true}，
     * 故执行器会把它收敛为内容链 failed（见 {@code ContentStepExecutorTest#criticalStepDegraded_failsTheChain}）。
     */
    @Test
    void hardCap10_overflowTruncatedAndDegraded() throws Exception {
        // 12 个目标 locale，超 cap 10 → 截断 2 个
        List<String> tooMany = IntStream.range(0, 12).mapToObj(i -> "l" + i).toList();
        var document = ContentDocs.masterWithListing(tooMany);
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        I18nBackfillStep step = new I18nBackfillStep(llm);
        // 排队翻译的 locale = 12 个，去掉源 locale "zh-CN" 剩 11（content master 默认有 zh-CN），
        // hard cap 10 → 实际只翻 10 个
        for (int i = 0; i < I18nBackfillStep.MAX_TARGET_LOCALES; i++) {
            llm.respond("translated-l" + i);
        }

        StepResult result = step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(result.outcome())
                .as("hard cap 触发应返回 DEGRADED，让执行器登记 degraded_steps")
                .isEqualTo(StepOutcome.DEGRADED);
        assertThat(result.reason()).contains("超过目标 locale 上限").contains("截断");
        // 实际翻译了 10 个（cap 内）
        assertThat(llm.requests()).hasSize(I18nBackfillStep.MAX_TARGET_LOCALES);
        // master canonical 里写满了 cap 个翻译
        long translatedLocales = working.spuTitles().entrySet().stream()
                .filter(e -> e.getValue().startsWith("translated-")).count();
        assertThat(translatedLocales).isEqualTo(I18nBackfillStep.MAX_TARGET_LOCALES);
    }
}
