package io.autocommerce.content;

import java.util.List;
import java.util.Map;

/**
 * 可空集合的读取口（#41 review 抽口）：core 标准模型的集合字段允许缺省（{@code null} = 该字段
 * 未采到，schema 里是 optional），读侧统一在这里收敛为空集合——避免每个调用点各写一遍
 * {@code x == null ? List.of() : x}（原先散在 {@code ContentWorkingSet} / {@code HumanContentEdit}
 * 约 18 处）。
 *
 * <p><b>只做"null → 空"，不做拷贝、不做 type 校验</b>：返回原引用，需要可变/不可变形态的调用方
 * 自行包装。与 {@code ai.StepValues} 的分工——那里是"StepContext 读到的 Object 要类型化 + 校验"，
 * 这里是"已经是正确静态类型的可空集合要兜空"。
 */
public final class NullSafe {

    private NullSafe() {
    }

    public static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }

    public static <K, V> Map<K, V> map(Map<K, V> value) {
        return value == null ? Map.of() : value;
    }
}
