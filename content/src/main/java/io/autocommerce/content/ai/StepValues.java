package io.autocommerce.content.ai;

import io.autocommerce.core.step.FieldRef;
import io.autocommerce.core.step.StepContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 侧取值助手：读 {@link StepContext#read}，并把它返回的 {@code Object}
 * 收敛成**带校验**的类型化视图。
 *
 * <p>为什么值得单独一处（#41 review）：{@code StepContext.read} 的返回类型是 {@code Object}
 * ——core 契约刻意不发明中间 DTO（specs/0006 §3），代价是 Step 侧必须下行转换。裸
 * {@code (Map<String,String>) value} 强转把类型错误推迟到使用点才炸，且报错只看得到实际类型、
 * 看不到是哪个字段；这里逐个元素校验并立即 {@link IllegalArgumentException}，把失败提前到
 * 读的当下，严格度与 {@code ListingStepContext.write} 同口径。
 *
 * <p><b>报错带字段路径（#48 补齐）</b>：三个助手都收 {@link StepContext} + {@link FieldRef}
 * （不是"值 + 标识"），故消息可以直接给出字段名——形如
 * {@code spu.titles 需要 Map<String,String>，实得 java.util.ArrayList}，与写侧
 * {@code ListingStepContext} 的 {@code stringMap} / {@code list} 同形（"键值必须是 String"那条保留
 * 读侧多出的 key/value 实得类型，写侧无此细节）。此前读侧消息只有实际类型，定位字段只能靠栈帧
 * 行号反推——#47 review 记录为待补缺口，本类当时是"缺字段路径"，不是与写侧有语义差异。
 * <b>注意 #48 票面举例的一处笔误</b>：票面把 {@code stringMap} 的类型不符写成"实得
 * java.util.HashMap"——{@code HashMap} 本身就是 {@code Map}，走的是正常分支、不抛错；"实得
 * HashMap"的真实归属是 {@code typedList} / {@code stringList}（拿 {@code Map} 冒充 {@code List}）。
 * 断言的夹具按此更正（见 {@code StepValuesTest} 的类 javadoc）。
 *
 * <p><b>为什么收 {@code (StepContext, FieldRef)} 而不是 {@code (FieldRef, Object)}（#48 取舍）</b>：
 * 后者要求调用点把同一个 {@code FieldRef} 构造两遍
 * （{@code stringMap(new FieldRef(X), context.read(new FieldRef(X)))}），两个实例必须始终一致，
 * 只改一边就会让报错指向错误字段；上提成局部变量则 11 处调用点每处多一行。收 {@code StepContext} 后
 * {@code read} 一并收进助手，调用点反而更短（{@code StepValues.stringMap(context, new FieldRef(...))}）。
 * 代价是本类不再是与 core 无关的纯 JDK 值转换器——{@code StepContext} / {@code FieldRef} 都在
 * {@code io.autocommerce.core} 内，{@code AI_STEPS_DEPEND_ON_CORE_CONTRACT_ONLY} 的白名单覆盖，
 * 不新增架构边。
 *
 * <p>缺省语义：{@code null} → 空集合（文档字段可缺省）；类型不符 → 立即失败（不静默返回空）。
 * 注意二者刻意不同——"字段没采到"与"字段形状不对"是两回事，后者是装配/契约错误。
 *
 * <p>放本包（{@code content.ai}）而非更外层：ArchUnit 的
 * {@code AI_STEPS_DEPEND_ON_CORE_CONTRACT_ONLY} 只允许内置 Step 依赖 core + JDK + Jackson
 * （ADR-0008"Step 接入面与社区 Step 完全一致"的机械表达）。
 *
 * <p><b>残留疑点（#43 收口时记录）：为什么不与 {@code ListingStepContext} 的写侧
 * {@code stringMap} / {@code list} 助手合并</b>——两侧都在做"逐元素校验的下行转换"，形态相近，
 * 但分处 {@code content.ai} 与 {@code content.step} 两包，合并只有两条路：
 * <ol>
 *   <li>本类上提到 {@code content.step}——内置 Step 的字节码里就会出现对 {@code ListingStepContext}
 *       的**指令级引用**。现状之所以能通过护栏：javac 把 {@code ListingStepContext.SPU_*} 这类
 *       {@code static final String} 常量编译期内联，常量池里虽仍残留一条
 *       {@code CONSTANT_Class} 条目，但没有任何指令引用它，ArchUnit 按访问取依赖、因此判不出来；
 *       一旦改成调用真实类（含静态方法），指令级引用出现，护栏立即红。<b>注意护栏绿 ≠ 架构上没有
 *       这条边</b>：5 个 Step 的源码都真实 {@code import io.autocommerce.content.step.ListingStepContext}
 *       并引用其 {@code SPU_*} 常量，当前"绿"是 javac 内联与 ArchUnit 只看字节码访问共同作用的
 *       结果。承重前提（那些常量必须一直是编译期常量）已交叉引用到 {@code ListingStepContext}
 *       常量块与 {@code ContentArchitectureTest} 的护栏 {@code because(...)}。</li>
 *   <li>{@code ListingStepContext} 反向依赖本类——方向 {@code step → ai}，层次倒置：通用的
 *       StepContext 实现去依赖某一个 Step 家族的助手。</li>
 * </ol>
 * 两条路的代价都高于"两份各约 20 行的校验"，故**维持现状**——这份重复是护栏的价格，不是漏抽的
 * 公共代码。注意"读侧报错不带字段路径"曾在此充当"两侧语义不同"的论据：该缺口已由 #48 补齐，
 * 故它现在连论据都不再成立，不作为不合并的理由。
 */
final class StepValues {

    private StepValues() {
    }

    /** 读 {@code Map<String,String>} 字段（如 {@code spu.titles} / {@code spu.descriptions}）。 */
    static Map<String, String> stringMap(StepContext context, FieldRef field) {
        String path = path(field);
        Object value = context.read(field);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(path + " 需要 Map<String,String>，实得 " + describe(value));
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String text)) {
                throw new IllegalArgumentException(path + " 的键值必须是 String，实得 key="
                        + describe(entry.getKey()) + " value=" + describe(entry.getValue()));
            }
            result.put(key, text);
        }
        return result;
    }

    /** 读 {@code List<String>} 字段（如 {@code listing.locales}）。 */
    static List<String> stringList(StepContext context, FieldRef field) {
        return typedList(context, field, String.class);
    }

    /** 读元素类型确定的列表字段（如 {@code spu.skus} / {@code media}）。 */
    static <T> List<T> typedList(StepContext context, FieldRef field, Class<T> type) {
        String path = path(field);
        Object value = context.read(field);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException(path + " 需要 List<" + type.getSimpleName() + ">，实得 "
                    + describe(value));
        }
        List<T> result = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (!type.isInstance(element)) {
                throw new IllegalArgumentException(path + " 元素类型须为 " + type.getSimpleName() + "，实得 "
                        + describe(element));
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

    /** 字段路径校验：与 {@code ListingStepContext.path(FieldRef)} 同口径（空路径立即失败，不读）。 */
    private static String path(FieldRef field) {
        if (field == null || field.path() == null || field.path().isBlank()) {
            throw new IllegalArgumentException("FieldRef.path 必填");
        }
        return field.path();
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
