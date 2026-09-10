package io.autocommerce.content.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.autocommerce.core.catalog.model.MediaAsset;
import io.autocommerce.core.catalog.model.ProcessingState;
import io.autocommerce.core.step.AiStep;
import io.autocommerce.core.step.FieldRef;
import io.autocommerce.core.step.MediaProcessRequest;
import io.autocommerce.core.step.MediaProcessResult;
import io.autocommerce.core.step.MediaProcessor;
import io.autocommerce.core.step.ModelRequirement;
import io.autocommerce.core.step.StepContext;
import io.autocommerce.core.step.StepDescriptor;
import io.autocommerce.core.step.StepExecutionException;
import io.autocommerce.core.step.StepResult;
import io.autocommerce.content.ContentPlan;
import io.autocommerce.content.step.ListingStepContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code media.process}（specs/0006 §8）：媒体机械处理 → MediaAsset 的 storage_ref / processing_state。
 *
 * <p>model_requirement = **MEDIA_PROCESSOR**——与 LLM 分型（specs/0006 §1"媒体处理 ≠ LLM"），
 * 经 core 的 {@link MediaProcessor} 接口执行，不走 LLMProvider 体系；生成式（图内文本本地化
 * {@code media.localize}）v1 预留不注册。
 *
 * <p>失败 = **不降级**（specs/0006 §8"硬依赖（无图不能铺）"）：抛 {@link StepExecutionException}，
 * 由计划 {@code critical=true} 决定内容链 failed。
 *
 * <p>幂等：已归档（storage_ref 非空且状态已推进）的资产跳过——内容链重跑不重复下载。
 */
public final class MediaProcessStep implements AiStep {

    public static final String ID = ContentPlan.MEDIA_PROCESS;

    private final MediaProcessor mediaProcessor;

    public MediaProcessStep(MediaProcessor mediaProcessor) {
        this.mediaProcessor = Objects.requireNonNull(mediaProcessor, "MediaProcessor 必填");
    }

    @Override
    public StepDescriptor descriptor() {
        return new StepDescriptor(
                ID,
                List.of(new FieldRef(ListingStepContext.MEDIA)),
                List.of(new FieldRef(ListingStepContext.MEDIA)),
                ModelRequirement.MEDIA_PROCESSOR,
                defaultParams());
    }

    static JsonNode defaultParams() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("operation", "archive");
        return params;
    }

    @Override
    public StepResult execute(StepContext context) throws StepExecutionException {
        String operation = StepParams.text(context.params(), "operation", "archive");
        List<MediaAsset> assets = mediaList(context.read(new FieldRef(ListingStepContext.MEDIA)));

        List<MediaAsset> updated = new ArrayList<>(assets);
        int processed = 0;
        boolean hasSource = assets.stream().anyMatch(a -> a.sourceUrl() != null && !a.sourceUrl().isBlank());
        if (!hasSource) {
            throw new StepExecutionException("无可用媒体源（无图不能铺）：文档内无带 source_url 的 MediaAsset");
        }

        for (int i = 0; i < updated.size(); i++) {
            MediaAsset asset = updated.get(i);
            if (asset.sourceUrl() == null || asset.sourceUrl().isBlank() || alreadyArchived(asset)) {
                continue;
            }
            MediaProcessResult result = mediaProcessor.process(
                    new MediaProcessRequest(asset.mediaId(), asset.sourceUrl(), operation, null));
            updated.set(i, new MediaAsset(asset.mediaId(), asset.sourceUrl(), result.storageRef(), asset.role(),
                    ProcessingState.DOWNLOADED, asset.variantOf(), asset.variantPurpose(),
                    result.variantLocale() == null ? asset.variantLocale() : result.variantLocale(),
                    asset.provenance()));
            processed++;
        }

        if (processed == 0) {
            return StepResult.ok();
        }
        context.write(new FieldRef(ListingStepContext.MEDIA), updated);
        return StepResult.ok();
    }

    private static boolean alreadyArchived(MediaAsset asset) {
        return asset.storageRef() != null && !asset.storageRef().isBlank()
                && asset.processingState() != ProcessingState.RAW
                && asset.processingState() != ProcessingState.FAILED;
    }

    @SuppressWarnings("unchecked")
    private static List<MediaAsset> mediaList(Object value) {
        return value == null ? List.of() : (List<MediaAsset>) value;
    }
}
