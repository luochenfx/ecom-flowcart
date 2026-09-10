package io.autocommerce.core.step;

import java.util.List;

/**
 * AI Step 的 Java SPI 注册发现入口（specs/0006 §3："注册发现 = Java SPI（AiStepProvider 声明
 * 实现清单）"）。与 Adapter 插件机制同哲学（ADR-0007/#9）：core 零 AI 平台依赖、无中央注册表，
 * classpath 上存在即被发现（{@code META-INF/services/io.autocommerce.core.step.AiStepProvider}）。
 *
 * <p>一个 provider 可声明多个 Step —— 首个内置实现在 content 模块（首批 Step 清单见 specs/0006 §8）。
 * 装配点（Composition Root）经 ServiceLoader 汇总各 provider 的 Step，按 id 建索引供内容链取用。
 */
public interface AiStepProvider {

    /** 本 provider 声明的 Step 清单（无状态、可重复调用）。 */
    List<AiStep> steps();
}
