package io.autocommerce.content;

import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.core.step.AiStep;
import io.autocommerce.core.step.ProviderException;
import io.autocommerce.core.step.StepOutcome;
import io.autocommerce.content.ai.DescGenerateStep;
import io.autocommerce.content.ai.I18nBackfillStep;
import io.autocommerce.content.ai.MediaProcessStep;
import io.autocommerce.content.ai.PriceStrategyStep;
import io.autocommerce.content.ai.TitleRewriteStep;
import io.autocommerce.content.testsupport.ContentDocs;
import io.autocommerce.content.testsupport.FakeLlmGateway;
import io.autocommerce.content.testsupport.FakeMediaProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内容链编排端到端（进程内）：顺序执行、单稿制 provenance、降级不阻断、硬依赖失败即 failed。
 */
class ContentChainServiceTest {

    private final FakeLlmGateway llm = new FakeLlmGateway();
    private final FakeMediaProcessor media = new FakeMediaProcessor();

    private ContentChainService chain() {
        List<AiStep> steps = List.of(
                new I18nBackfillStep(llm), new TitleRewriteStep(llm), new DescGenerateStep(llm),
                new PriceStrategyStep(), new MediaProcessStep(media));
        return new ContentChainService(new ContentStepExecutor(steps, ContentDocs.FIXED_CLOCK));
    }

    @Test
    void standardPlan_producesContentReadyListing() {
        llm.respond("Wireless Bluetooth Earphones")   // i18n.backfill
                .respond("降噪蓝牙耳机 长续航")            // title.rewrite (zh-CN)
                .respond("ANC Wireless Earphones")     // title.rewrite (en)
                .respond("卖点描述文案")                   // desc.generate (zh-CN)
                .respond("Selling point copy");        // desc.generate (en)

        ContentChainOutcome outcome = chain().run(ContentDocs.masterWithListing(), ContentDocs.listingId(),
                ContentPlan.standard());

        assertThat(outcome.result().contentReady()).isTrue();
        assertThat(outcome.result().runs()).extracting(ContentStepRun::stepId)
                .containsExactly("i18n.backfill", "title.rewrite", "desc.generate", "price.strategy",
                        "media.process");
        assertThat(outcome.result().runs()).allMatch(r -> r.outcome() == StepOutcome.OK);
        assertThat(outcome.result().degradedSteps()).isEmpty();

        var listing = ContentDocs.listing(outcome.document(), ContentDocs.listingId());
        assertThat(listing.titleOverrides()).containsKeys("zh-CN", "en");
        assertThat(listing.descriptionOverrides()).containsKeys("zh-CN", "en");
        assertThat(listing.skuSet()).extracting(s -> s.price().amount()).containsExactly("82.62", "93.60");

        // 单稿制：AI 直写 + provenance.updated_by_step=AI（created_by_step 保留）
        assertThat(listing.provenance().updatedByStep()).isEqualTo(ProvenanceStep.AI);
        assertThat(listing.provenance().createdByStep()).isEqualTo(ProvenanceStep.LISTING);
        assertThat(outcome.document().spus().get(0).provenance().updatedByStep()).isEqualTo(ProvenanceStep.AI);
        assertThat(outcome.document().mediaAssets().get(0).provenance().updatedByStep())
                .isEqualTo(ProvenanceStep.AI);
        // 译文回填 canonical（一处翻译多处复用）
        assertThat(outcome.document().spus().get(0).titles()).containsKey("en");
    }

    @Test
    void optionalStepsDegrade_withoutBlockingTheChain() {
        var plan = ContentPlan.standard().without(ContentPlan.I18N_BACKFILL);
        llm.failWith(new ProviderException("endpoint down"));

        ContentChainOutcome outcome = chain().run(ContentDocs.masterWithListing(), ContentDocs.listingId(), plan);

        assertThat(outcome.result().contentReady()).isTrue();
        assertThat(outcome.result().runs()).filteredOn(r -> r.outcome() == StepOutcome.DEGRADED)
                .extracting(ContentStepRun::stepId)
                .containsExactly("title.rewrite", "desc.generate");
        assertThat(outcome.result().degradedSteps()).extracting("step")
                .containsExactly("title.rewrite", "desc.generate");

        // 降级产物 = 缺省（overrides 不写，铺货回退 canonical）；其余 Step 产物照落
        var listing = ContentDocs.listing(outcome.document(), ContentDocs.listingId());
        assertThat(listing.titleOverrides()).isNull();
        assertThat(listing.skuSet()).extracting(s -> s.price().amount()).containsExactly("82.62", "93.60");
        assertThat(listing.degradedSteps()).hasSize(2);
    }

    /**
     * 端点恢复后重跑同一条链：历史降级留痕必须消失——degraded_steps 表达"当前内容缺口"，
     * 不是"曾经出过问题"的流水账；否则已补齐的 Listing 会一直对看板亮 HITL。
     */
    @Test
    void rerunAfterRecovery_clearsStaleDegradedSteps() {
        ContentPlan plan = ContentPlan.standard().without(ContentPlan.I18N_BACKFILL);

        llm.failWith(new ProviderException("endpoint down"));
        ContentChainOutcome first = chain().run(ContentDocs.masterWithListing(), ContentDocs.listingId(), plan);
        assertThat(first.result().degradedSteps()).extracting("step")
                .containsExactly(ContentPlan.TITLE_REWRITE, ContentPlan.DESC_GENERATE);
        assertThat(ContentDocs.listing(first.document(), ContentDocs.listingId()).degradedSteps()).hasSize(2);

        // 恢复后在上一轮产物文档上重跑（canonical 仍只有 zh-CN，故每步各一次调用）
        llm.recovers().respond("改写后的标题").respond("生成的描述");
        ContentChainOutcome second = chain().run(first.document(), ContentDocs.listingId(), plan);

        assertThat(second.result().degradedSteps()).isEmpty();
        assertThat(ContentDocs.listing(second.document(), ContentDocs.listingId()).degradedSteps()).isEmpty();
        assertThat(ContentDocs.listing(second.document(), ContentDocs.listingId()).titleOverrides())
                .containsKey("zh-CN");
    }

    @Test
    void hardDependencyFailure_marksChainFailed() {
        llm.failWith(new ProviderException("endpoint down"));

        assertThatThrownBy(() -> chain().run(ContentDocs.masterWithListing(), ContentDocs.listingId(),
                ContentPlan.standard()))
                .isInstanceOf(ContentChainFailedException.class)
                .hasMessageContaining("i18n.backfill");
    }

    @Test
    void mediaStepIsHardDependency_byDefaultPlan() {
        llm.respond("t").respond("t").respond("t").respond("d").respond("d");
        media.failWith(new io.autocommerce.core.step.StepExecutionException("对象存储不可用"));

        assertThatThrownBy(() -> chain().run(ContentDocs.masterWithListing(), ContentDocs.listingId(),
                ContentPlan.standard()))
                .isInstanceOf(ContentChainFailedException.class)
                .hasMessageContaining("media.process")
                .hasMessageContaining("对象存储不可用");
    }

    @Test
    void missingSteps_surfacePlanVsSpiDrift() {
        ContentPlan plan = new ContentPlan(List.of(
                new ContentPlan.PlanStep(ContentPlan.TITLE_REWRITE, false),
                new ContentPlan.PlanStep("media.localize", false)));

        assertThat(chain().missingSteps(plan)).containsExactly("media.localize");
    }

    @Test
    void executionRecord_carriesModelAndTokens() {
        llm.respond("t").respond("t").respond("t").respond("d").respond("d");

        ContentChainOutcome outcome = chain().run(ContentDocs.masterWithListing(List.of("zh-CN")),
                ContentDocs.listingId(), ContentPlan.standard());

        ContentStepRun title = outcome.result().runs().stream()
                .filter(r -> r.stepId().equals(ContentPlan.TITLE_REWRITE)).findFirst().orElseThrow();
        assertThat(title.model()).isEqualTo("fake-model");
        assertThat(title.totalTokens()).isEqualTo(46);
        ContentStepRun price = outcome.result().runs().stream()
                .filter(r -> r.stepId().equals(ContentPlan.PRICE_STRATEGY)).findFirst().orElseThrow();
        assertThat(price.model()).isNull();
        assertThat(price.totalTokens()).isNull();
    }
}
