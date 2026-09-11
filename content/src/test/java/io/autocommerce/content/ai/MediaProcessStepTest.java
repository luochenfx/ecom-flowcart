package io.autocommerce.content.ai;

import io.autocommerce.core.catalog.model.MediaAsset;
import io.autocommerce.core.catalog.model.ProcessingState;
import io.autocommerce.core.step.StepExecutionException;
import io.autocommerce.core.step.StepOutcome;
import io.autocommerce.core.step.StepResult;
import io.autocommerce.content.model.ContentWorkingSet;
import io.autocommerce.content.step.ListingStepContext;
import io.autocommerce.content.testsupport.ContentDocs;
import io.autocommerce.content.testsupport.FakeMediaProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * media.process（specs/0006 §8）：MEDIA_PROCESSOR 分型（与 LLM 不混同），硬依赖不降级。
 */
class MediaProcessStepTest {

    private final FakeMediaProcessor processor = new FakeMediaProcessor();
    private final MediaProcessStep step = new MediaProcessStep(processor);

    @Test
    void archivesRawAssets_andAdvancesProcessingState() throws Exception {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());

        StepResult result = step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(result.outcome()).isEqualTo(StepOutcome.OK);
        assertThat(processor.requests()).hasSize(2);
        assertThat(processor.requests()).extracting("operation").containsOnly("archive");
        assertThat(working.mediaAssets())
                .allMatch(a -> a.processingState() == ProcessingState.DOWNLOADED)
                .allMatch(a -> a.storageRef() != null && a.storageRef().startsWith("fake-store/"));
    }

    @Test
    void skipsAlreadyArchivedAssets_idempotentOnRerun() throws Exception {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        working.mediaAssets(document.mediaAssets().stream()
                .map(a -> new MediaAsset(a.mediaId(), a.sourceUrl(), "fake-store/" + a.mediaId() + ".jpg",
                        a.role(), ProcessingState.DOWNLOADED, a.variantOf(), a.variantPurpose(),
                        a.variantLocale(), a.provenance()))
                .toList());

        StepResult result = step.execute(new ListingStepContext(working, step.descriptor().params()));

        assertThat(result.outcome()).isEqualTo(StepOutcome.OK);
        assertThat(processor.requests()).isEmpty();
    }

    @Test
    void noMediaSource_isHardFailure_notDegrade() {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        working.mediaAssets(document.mediaAssets().stream()
                .map(a -> new MediaAsset(a.mediaId(), null, null, a.role(), ProcessingState.RAW,
                        a.variantOf(), a.variantPurpose(), a.variantLocale(), a.provenance()))
                .toList());

        assertThatThrownBy(() -> step.execute(new ListingStepContext(working, step.descriptor().params())))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("无图不能铺");
    }

    @Test
    void processorFailure_propagatesAsHardFailure() {
        var document = ContentDocs.masterWithListing();
        ContentWorkingSet working = ContentWorkingSet.of(document, ContentDocs.listingId());
        processor.failWith(new StepExecutionException("归档失败"));

        assertThatThrownBy(() -> step.execute(new ListingStepContext(working, step.descriptor().params())))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("归档失败");
        assertThat(working.mediaAssets()).allMatch(a -> a.processingState() == ProcessingState.RAW);
    }

    @Test
    void descriptor_usesMediaProcessorTyping() {
        var descriptor = step.descriptor();

        assertThat(descriptor.id()).isEqualTo("media.process");
        assertThat(descriptor.modelRequirement().name()).isEqualTo("MEDIA_PROCESSOR");
        assertThat(descriptor.input()).extracting("path").containsExactly("media");
        assertThat(descriptor.output()).extracting("path").containsExactly("media");
        assertThat(List.of()).isEmpty();
    }
}
