package io.autocommerce.core.step;

/**
 * 媒体处理器（specs/0006 §1/§4）。媒体机械处理（去水印/格式/尺寸）走独立接口，与 LLM 分型；
 * 媒体 Step 的 model_requirement = MEDIA_PROCESSOR，经本接口执行。生成式（图内文本本地化）
 * v1 预留不实现。
 */
public interface MediaProcessor {

    MediaProcessResult process(MediaProcessRequest request) throws StepExecutionException;
}
