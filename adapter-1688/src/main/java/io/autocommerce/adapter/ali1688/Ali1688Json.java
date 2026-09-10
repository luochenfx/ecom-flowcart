package io.autocommerce.adapter.ali1688;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 1688 响应取值helper（Adapter 内共享）。
 *
 * <p>1688 各端点的响应包装<b>命名不统一</b>（{@code success/code/message}、
 * {@code errorCode/errorMessage}、{@code errorMsg}），且同一字段在不同 offer 上可能是
 * 文本 / 数值 / 缺失——取值一律走这里，避免每个 mapper 各写一份"是值节点才读"的分支。
 */
final class Ali1688Json {

    private Ali1688Json() {
    }

    /** 取值节点的文本；不是值节点（对象 / 数组 / 缺失）→ {@code null}。 */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() ? value.asText() : null;
    }

    /**
     * 按候选字段名依次取值，返回第一个非空文本（1688 错误字段三套命名共存）；
     * 全部落空 → {@code null}。
     */
    static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
