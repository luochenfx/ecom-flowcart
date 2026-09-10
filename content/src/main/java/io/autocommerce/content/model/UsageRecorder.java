package io.autocommerce.content.model;

import io.autocommerce.core.step.ChatUsage;

/**
 * Step 调用用量收集口（specs/0006 §7"每次调用 token 用量/成本落执行记录"的实现通道）。
 *
 * <p>Step 本身不感知用量：LLM 调用经 {@code LlmGateway} 走统一的 provider 出口，网关把每次
 * {@code ChatResponse.usage} 记到执行期上下文（本接口），由内容链汇总进
 * {@code ContentStepRun}（供看板聚合 token/成本，不建计费系统）。
 *
 * <p>调用方 = 内容链模块内的 LLM 网关；实现方 = {@link io.autocommerce.content.step.ListingStepContext}。
 * 二者同模块，装配期不会出现第三方实现——接口在此显式声明，避免 Step 契约（core）被用量语义污染。
 */
public interface UsageRecorder {

    /** 记录一次模型调用的用量（model 为空表示 provider 默认模型）。 */
    void recordUsage(String model, ChatUsage usage);
}
