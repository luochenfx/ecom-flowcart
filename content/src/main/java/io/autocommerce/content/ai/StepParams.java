package io.autocommerce.content.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Step params 读取助手（params 形态 = {@code StepDescriptor.params}，JsonNode）。
 * 缺项取默认、类型不符取默认——Step 参数是"可配置项"，不是契约字段，不做严格校验（例外见各 Step）。
 */
final class StepParams {

    private StepParams() {
    }

    static String text(JsonNode params, String key, String fallback) {
        if (params == null) {
            return fallback;
        }
        JsonNode value = params.get(key);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    static Double number(JsonNode params, String key, Double fallback) {
        if (params == null) {
            return fallback;
        }
        JsonNode value = params.get(key);
        if (value == null) {
            return fallback;
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        if (value.isTextual()) {
            try {
                return Double.parseDouble(value.asText().trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    static Integer integer(JsonNode params, String key, Integer fallback) {
        Double value = number(params, key, null);
        return value == null ? fallback : value.intValue();
    }

    static List<String> stringList(JsonNode params, String key) {
        if (params == null) {
            return List.of();
        }
        JsonNode value = params.get(key);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (element.isTextual() && !element.asText().isBlank()) {
                result.add(element.asText());
            }
        }
        return result;
    }
}
