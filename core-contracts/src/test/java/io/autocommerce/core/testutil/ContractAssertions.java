package io.autocommerce.core.testutil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约断言助手（Testing Decisions §1：双向契约测试的共享门）。
 * <ul>
 *   <li>assertValid = 文档过 schema 校验（模型 → JSON 方向的门）；</li>
 *   <li>assertSemanticallyEquals = JSON 规范化后语义等价（无字段丢失门，键序/null≡省略/空白不敏感）。</li>
 * </ul>
 */
public final class ContractAssertions {

    private ContractAssertions() {
    }

    /** 断言 doc 通过 schema 校验；失败列出全部校验消息。 */
    public static void assertValid(JsonSchema schema, JsonNode doc, String context) {
        Set<ValidationMessage> messages = schema.validate(doc);
        assertThat(messages)
                .as("%s 应通过 JSON Schema 校验\n%s", context, describe(messages))
                .isEmpty();
    }

    /**
     * 断言实际 JSON 与期望语义等价：比较前将两侧做 null 规范化（null 值字段 ≡ 省略，
     * 与 NON_NULL 序列化语义一致），再以 canonical 文本比较（键序不敏感；数字按数值等价——
     * schema number 无 scale 语义，148.0 ≡ 148.00 ≡ 148）。
     */
    public static void assertSemanticallyEquals(JsonNode expected, JsonNode actual, String context) {
        String expectedText = canonicalText(normalizeNulls(expected.deepCopy()));
        String actualText = canonicalText(normalizeNulls(actual.deepCopy()));
        assertThat(actualText)
                .as("%s round-trip 应无字段丢失\n实际: %s\n期望(规范化): %s",
                        context, actualText, expectedText)
                .isEqualTo(expectedText);
    }

    /** 递归删除值为 null 的字段（null ≡ 省略）。 */
    public static JsonNode normalizeNulls(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            Iterator<String> names = obj.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode child = obj.get(name);
                if (child.isNull()) {
                    names.remove();
                } else {
                    obj.set(name, normalizeNulls(child));
                }
            }
            return obj;
        }
        if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, normalizeNulls(arr.get(i)));
            }
        }
        return node;
    }

    /** 规范文本：object 键排序、array 保序、数字按数值等价表达。 */
    private static String canonicalText(JsonNode node) {
        if (node.isObject()) {
            List<String> parts = new ArrayList<>();
            node.properties().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(e -> parts.add('"' + e.getKey() + "\":" + canonicalText(e.getValue())));
            return "{" + String.join(",", parts) + "}";
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canonicalText(node.get(i)));
            }
            return sb.append(']').toString();
        }
        if (node.isNumber()) {
            // schema number 无 scale 语义：统一按数值规范化（BigDecimal 去尾零），
            // 148.0 ≡ 148.00 ≡ 148、浮点 2.0 与整数 2 亦判等（DoubleNode/DecimalNode 同值同形）
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (node.isTextual()) {
            // JSON 引号转义形态：字符串 "123" 与数值 123、字符串 "true" 与布尔 true 不再可混淆，
            // 保证"无字段丢失"门对类型漂移敏感（AC-2 契约保证不因文本裸拼而失效）
            return node.toString();
        }
        if (node.isBoolean()) {
            return Boolean.toString(node.booleanValue());
        }
        return node.asText();
    }

    /** 收集校验消息为一行可读文本（测试失败时输出）。 */
    public static String describe(Set<ValidationMessage> messages) {
        return messages.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
    }
}
