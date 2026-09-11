package io.autocommerce.content.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 侧取值助手：把 {@link io.autocommerce.core.step.StepContext#read} 返回的 {@code Object}
 * 收敛成**带校验**的类型化视图。
 *
 * <p>为什么值得单独一处（#41 review）：{@code StepContext.read} 的返回类型是 {@code Object}
 * ——core 契约刻意不发明中间 DTO（specs/0006 §3），代价是 Step 侧必须下行转换。裸
 * {@code (Map<String,String>) value} 强转把类型错误推迟到使用点才炸，且栈里看不出是哪个字段；
 * 这里逐个元素校验并立即 {@link IllegalArgumentException}，与
 * {@code ListingStepContext.write} 的严格校验同口径，只是补在读侧。
 *
 * <p>缺省语义：{@code null} → 空集合（文档字段可缺省）；类型不符 → 立即失败（不静默返回空）。
 * 注意二者刻意不同——"字段没采到"与"字段形状不对"是两回事，后者是装配/契约错误。
 *
 * <p>放本包（{@code content.ai}）而非更外层：ArchUnit 的
 * {@code AI_STEPS_DEPEND_ON_CORE_CONTRACT_ONLY} 只允许内置 Step 依赖 core + JDK + Jackson
 * （ADR-0008"Step 接入面与社区 Step 完全一致"的机械表达）。
 */
final class StepValues {

    private StepValues() {
    }

    /** 读 {@code Map<String,String>} 字段（如 {@code spu.titles} / {@code spu.descriptions}）。 */
    static Map<String, String> stringMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("StepContext 读到的值应为 Map<String,String>，实得 "
                    + value.getClass().getName());
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String text)) {
                throw new IllegalArgumentException("Map 的键值应为 String，实得 key=" + describe(entry.getKey())
                        + " value=" + describe(entry.getValue()));
            }
            result.put(key, text);
        }
        return result;
    }

    /** 读 {@code List<String>} 字段（如 {@code listing.locales}）。 */
    static List<String> stringList(Object value) {
        return typedList(value, String.class);
    }

    /** 读元素类型确定的列表字段（如 {@code spu.skus} / {@code media}）。 */
    static <T> List<T> typedList(Object value, Class<T> type) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException("StepContext 读到的值应为 List<" + type.getSimpleName()
                    + ">，实得 " + value.getClass().getName());
        }
        List<T> result = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (!type.isInstance(element)) {
                throw new IllegalArgumentException("列表元素应为 " + type.getSimpleName()
                        + "，实得 " + describe(element));
            }
            result.add(type.cast(element));
        }
        return result;
    }

    /** 文本缺口判定（Step 侧统一口径，勿各处散写 {@code value == null || value.isBlank()}）。 */
    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static boolean nonBlank(String value) {
        return !blank(value);
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
