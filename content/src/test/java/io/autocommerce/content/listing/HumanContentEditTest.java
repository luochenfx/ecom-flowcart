package io.autocommerce.content.listing;

import io.autocommerce.core.catalog.model.DegradedStep;
import io.autocommerce.core.catalog.model.Listing;
import io.autocommerce.core.catalog.model.ProvenanceStep;
import io.autocommerce.content.ContentChainOutcome;
import io.autocommerce.content.ContentChainService;
import io.autocommerce.content.ContentPlan;
import io.autocommerce.content.ContentStepExecutor;
import io.autocommerce.content.testsupport.ContentDocs;
import io.autocommerce.content.testsupport.FakeLlmGateway;
import io.autocommerce.content.testsupport.FakeMediaProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单稿制（specs/0006 §6 / #20 AC-5）：AI 直写 → 人工编辑置 HUMAN；
 * 无候选/采纳两态、无版本表；degraded_steps 留痕不被人工编辑吞掉。
 */
class HumanContentEditTest {

    private final HumanContentEdit editor = new HumanContentEdit(ContentDocs.FIXED_CLOCK);

    @Test
    void humanEdit_marksProvenanceHuman_andPreservesCanonicalFields() {
        var document = ContentDocs.masterWithListing();

        var edited = editor.apply(document, ContentDocs.listingId(),
                Map.of("zh-CN", "人工改写的标题"), Map.of("en", "Human written description"));

        Listing listing = ContentDocs.listing(edited, ContentDocs.listingId());
        assertThat(listing.titleOverrides()).containsExactly(Map.entry("zh-CN", "人工改写的标题"));
        assertThat(listing.descriptionOverrides()).containsExactly(Map.entry("en", "Human written description"));
        assertThat(listing.provenance().updatedByStep()).isEqualTo(ProvenanceStep.HUMAN);
        assertThat(listing.provenance().createdByStep()).isEqualTo(ProvenanceStep.LISTING);
        assertThat(listing.provenance().updatedAt()).isEqualTo("2026-09-10T12:00:00Z");
        // 其余字段原样（人工只动被改的字段）
        assertThat(listing.skuSet()).isEqualTo(ContentDocs.listing(document, ContentDocs.listingId()).skuSet());
    }

    @Test
    void humanEdit_mergesWithAiDraft_keepingOtherLocales() {
        var ai = runChainWithDegradedTitle();

        var edited = editor.apply(ai, ContentDocs.listingId(), Map.of("zh-CN", "人工标题"), Map.of());

        Listing listing = ContentDocs.listing(edited, ContentDocs.listingId());
        // AI 已写的 en 描述保留，人工只覆盖 zh-CN 标题
        assertThat(listing.descriptionOverrides()).containsKey("en");
        assertThat(listing.titleOverrides()).containsOnlyKeys("zh-CN");
        assertThat(listing.provenance().updatedByStep()).isEqualTo(ProvenanceStep.HUMAN);
        // 降级留痕保留（人工改一处 ≠ 认领 HITL 复核）
        assertThat(listing.degradedSteps()).extracting(DegradedStep::step).contains("title.rewrite");
    }

    @Test
    void rejectsNoOpEditAndUnknownListing() {
        var document = ContentDocs.masterWithListing();

        assertThatThrownBy(() -> editor.apply(document, ContentDocs.listingId(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("至少需给出");
        assertThatThrownBy(() -> editor.apply(document, "listing-x", Map.of("zh-CN", "t"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("文档内无 Listing");
    }

    /**
     * 跑一遍内容链：先翻译回填成功（canonical 补齐 en），再让标题改写降级、描述生成成功——
     * 拿到"AI 已写多语言产物 + 一条降级留痕"的文档，供人工编辑叠加测试。
     *
     * <p>两阶段跑法（同一工作集续跑）是有意的：`failNext` 只对下一次调用生效，若把翻译回填并进
     * 同一计划，失败会落在翻译（硬依赖）而非标题改写上。分阶段精确命中目标 Step。
     */
    private io.autocommerce.core.catalog.model.ProductCatalog runChainWithDegradedTitle() {
        FakeLlmGateway llm = new FakeLlmGateway();
        FakeMediaProcessor media = new FakeMediaProcessor();
        ContentStepExecutor executor = new ContentStepExecutor(List.of(
                new io.autocommerce.content.ai.I18nBackfillStep(llm),
                new io.autocommerce.content.ai.TitleRewriteStep(llm),
                new io.autocommerce.content.ai.DescGenerateStep(llm),
                new io.autocommerce.content.ai.PriceStrategyStep(),
                new io.autocommerce.content.ai.MediaProcessStep(media)), ContentDocs.FIXED_CLOCK);
        ContentChainService chain = new ContentChainService(executor);
        io.autocommerce.content.model.ContentWorkingSet working =
                io.autocommerce.content.model.ContentWorkingSet.of(
                        ContentDocs.masterWithListing(), ContentDocs.listingId());

        // 阶段 1：翻译回填（成功）——canonical 补齐 en，描述生成才有 en 素材
        llm.respond("Wireless Bluetooth Earphones");
        chain.run(working, new ContentPlan(List.of(
                new ContentPlan.PlanStep(ContentPlan.I18N_BACKFILL, true))));

        // 阶段 2：标题改写失败（非硬依赖 → 降级），描述生成成功 → 两个 locale 均有产物
        llm.failNext(new io.autocommerce.core.step.ProviderException("title endpoint down"));
        ContentChainOutcome outcome = chain.run(working,
                ContentPlan.standard().without(ContentPlan.I18N_BACKFILL));

        assertThat(outcome.result().degradedSteps()).extracting(DegradedStep::step)
                .containsExactly("title.rewrite");
        assertThat(ContentDocs.listing(outcome.document(), ContentDocs.listingId()).descriptionOverrides())
                .containsKeys("zh-CN", "en");
        return outcome.document();
    }
}
