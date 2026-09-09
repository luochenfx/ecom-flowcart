package io.autocommerce.core.testutil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ContractAssertions 语义等价门的健壮性测试（review #17 finding 的回归钉）。
 * 门的核心不变量：字符串与数字/布尔即使文本长相相同（"123" vs 123、"true" vs true）
 * 也不得判等 —— 否则"JSON→模型无字段丢失"的断言会对类型漂移失明。
 */
class ContractAssertionsTest {

    private static final ObjectMapper MAPPER = ContractObjectMapper.create();

    private static JsonNode json(String src) throws Exception {
        return MAPPER.readTree(src);
    }

    @Test
    void textualAndNumberWithSameTextDoNotCollide() throws Exception {
        // "123"（字符串）与 123（数值）规范化文本必须不同
        assertThatCode(() -> ContractAssertions.assertSemanticallyEquals(
                json("{\"v\":\"123\"}"), json("{\"v\":123}"), "string-vs-number"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void textualAndBooleanWithSameTextDoNotCollide() throws Exception {
        // "true"（字符串）与 true（布尔）规范化文本必须不同
        assertThatCode(() -> ContractAssertions.assertSemanticallyEquals(
                json("{\"v\":\"true\"}"), json("{\"v\":true}"), "string-vs-boolean"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void keyOrderAndNullOmissionStayInsensitive() throws Exception {
        // 键序与 null ≡ 省略的语义保持不敏感（既有承诺不回退）
        ContractAssertions.assertSemanticallyEquals(
                json("{\"b\":2,\"a\":\"x\",\"skip\":null}"),
                json("{\"a\":\"x\",\"b\":2.0}"),
                "key-order-null-insensitive");
    }
}
