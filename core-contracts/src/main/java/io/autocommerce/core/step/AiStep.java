package io.autocommerce.core.step;

/**
 * AI Step（specs/0006 §3）。无状态可插拔内容处理插件：读标准模型字段 → 处理 → 写回字段。
 * Java SPI 注册发现（与 Adapter 插件同哲学：core 零 AI 平台依赖、无中央注册表）。
 * 单 Step 可重跑/可跳过/产物可审计（provenance.step）。
 */
public interface AiStep {

    StepDescriptor descriptor();

    StepResult execute(StepContext context) throws StepExecutionException;
}
