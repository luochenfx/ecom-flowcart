package io.autocommerce.core.step;

/**
 * Step 模型需求三分（specs/0006 §1）。媒体处理 ≠ LLM：两类能力在 Step 抽象上分型，
 * 不混在一个 provider 体系。price.strategy 可纯规则零模型（RULE）。
 */
public enum ModelRequirement {
    /** 文本生成/改写/翻译 —— LLMProvider */
    LLM,
    /** 媒体机械处理（去水印/格式/尺寸）—— MediaProcessor */
    MEDIA_PROCESSOR,
    /** 纯规则（价格加价等），零模型 */
    RULE
}
