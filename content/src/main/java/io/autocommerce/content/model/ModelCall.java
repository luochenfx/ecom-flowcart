package io.autocommerce.content.model;

import io.autocommerce.core.step.ChatUsage;

/**
 * 一次模型调用的用量记录（specs/0006 §7）：随内容链执行记录聚合，供看板看 token/成本，
 * 不建计费系统。
 */
public record ModelCall(String model, ChatUsage usage) {
}
