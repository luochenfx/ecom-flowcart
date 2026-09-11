package io.autocommerce.worker.content;

import io.autocommerce.catalog.store.JsonFileCatalogStore;
import io.autocommerce.content.ContentChainFailedException;
import io.autocommerce.content.ContentPlan;
import io.autocommerce.content.ContentStepExecutor;
import io.autocommerce.content.ai.I18nBackfillStep;
import io.autocommerce.content.testsupport.ContentDocs;
import io.autocommerce.content.testsupport.FakeLlmGateway;
import io.autocommerce.worker.event.NoopEventPublisher;
import io.autocommerce.worker.event.SysWorkflowFailedEvent;
import io.autocommerce.worker.event.WorkflowFailedReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * activity 侧的前置条件（不依赖 Temporal 环境，直接调实现）。
 *
 * <p>内容链的前置 = 文档已落库（#19 采集或 Listing 装配写回）。缺文档是**装配/时序错误**而非业务
 * 降级，必须显式失败、不能被当成"内容为空"静默继续——那样会产出一份内容未就绪却被判就绪的 Listing。
 */
class ContentChainActivitiesImplTest {

    @TempDir
    Path tempDir;

    @Test
    void missingDocument_isAssemblyStateError() {
        ContentChainActivitiesImpl activities = new ContentChainActivitiesImpl(
                new JsonFileCatalogStore(tempDir), new ContentStepExecutor(List.of(), Clock.systemUTC()),
                "ContentWorkflow", new WorkflowCoordinates() {
                    @Override
                    public String workflowId() {
                        return "test-workflow-id";
                    }

                    @Override
                    public String runId() {
                        return "test-run-id";
                    }
                });

        assertThatThrownBy(() -> activities.degradedSteps(new ListingRef("spu-x", "listing-x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无此 SPU 文档");
        assertThatThrownBy(() -> activities.runStep(
                new ContentStepInput(new ListingRef("spu-x", "listing-x"), "i18n.backfill", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无此 SPU 文档");
    }

    /**
     * AC-6：硬依赖 Step 失败时 activity 应发 {@code sys.workflow.failed} 事件，再抛回 Temporal
     * （不让异常被吞，让 workflow 走 failed）。事件 payload = Temporal workflowId + runId + 失败 Step +
     * 原因；**不依赖业务库行**（避免双写一致性）。
     *
     * <p>触发场景：i18n.backfill 缺源 locale 标题 → 抛 StepExecutionException + planStep.critical=true
     * → ContentStepExecutor 包成 ContentChainFailedException → activity 捕获后发事件再重抛。
     */
    @Test
    void hardStepFailure_publishesSysWorkflowFailedEvent() {
        var store = new JsonFileCatalogStore(tempDir.resolve("catalog"));
        var document = ContentDocs.masterWithListing();
        // 清掉 spu 标题 → i18n.backfill 走缺源 locale 硬失败路径
        var spu = document.spus().get(0);
        var spuWithoutTitle = new io.autocommerce.core.catalog.model.Spu(
                spu.spuId(), spu.sourceRef(), java.util.Map.of(),
                spu.descriptions(), spu.images(), spu.skus(),
                spu.sourceCategories(), spu.attributes(), spu.platformRaw(), spu.provenance());
        var documentNoTitle = new io.autocommerce.core.catalog.model.ProductCatalog(
                document.schemaVersion(),
                java.util.List.of(spuWithoutTitle),
                document.skus(),
                document.listings(),
                document.mediaAssets());
        store.put(documentNoTitle);

        FakeLlmGateway llm = new FakeLlmGateway();
        var executor = new ContentStepExecutor(
                List.of(new I18nBackfillStep(llm)), ContentDocs.FIXED_CLOCK);
        NoopEventPublisher events = new NoopEventPublisher();

        ContentChainActivitiesImpl activities = new ContentChainActivitiesImpl(
                store, executor, events, "ContentWorkflow",
                new WorkflowCoordinates() {
                    @Override
                    public String workflowId() {
                        return "content-test-listing-id";
                    }

                    @Override
                    public String runId() {
                        return "run-test-id";
                    }
                });

        assertThatThrownBy(() -> activities.runStep(new ContentStepInput(
                new ListingRef(ContentDocs.SPU_ID, ContentDocs.listingId()),
                ContentPlan.I18N_BACKFILL, true)))
                .isInstanceOf(ContentChainFailedException.class)
                .hasMessageContaining(ContentPlan.I18N_BACKFILL);

        assertThat(events.publishedFailedEvents())
                .as("硬依赖失败 → activity 必发 sys.workflow.failed（A-prime 降级语义）")
                .hasSize(1);
        SysWorkflowFailedEvent published = events.publishedFailedEvents().get(0);
        assertThat(published.workflowType()).isEqualTo("ContentWorkflow");
        assertThat(published.workflowId()).isEqualTo("content-test-listing-id");
        assertThat(published.runId()).isEqualTo("run-test-id");
        assertThat(published.spuId()).isEqualTo(ContentDocs.SPU_ID);
        assertThat(published.listingId()).isEqualTo(ContentDocs.listingId());
        assertThat(published.failedStep()).isEqualTo(ContentPlan.I18N_BACKFILL);
        assertThat(published.errorType()).isEqualTo(WorkflowFailedReason.NON_RETRYABLE);
        assertThat(published.reason()).contains("缺来源 locale");
    }
}
