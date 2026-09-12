package io.autocommerce.content.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.autocommerce.core.catalog.model.ListingSku;
import io.autocommerce.core.catalog.model.Money;
import io.autocommerce.core.step.FieldRef;
import io.autocommerce.core.step.StepContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StepValues} 读侧助手（#48）：异常消息必须带字段路径——此前消息只有实际类型，定位字段
 * 只能靠栈帧行号反推，与写侧 {@code ListingStepContext} 不同口径。
 *
 * <p>用**手写 fake** {@link StepContext}（本 repo 硬约束：不引 mock 框架）：只有"按 {@code FieldRef}
 * 取值"是被测行为，其余方法直接失败，避免测试顺手通过。
 *
 * <p>夹具注意：触发 {@code stringMap} 的"值不是 Map"分支要用**非 Map**（如 {@code List}），
 * 不能拿 {@code HashMap} 冒充——{@code HashMap} 本身就是 {@code Map}，走的是正常分支。（#48 的
 * 票面举例把这条写成了"实得 java.util.HashMap"，那是 {@code typedList} 分支的形态。）
 */
class StepValuesTest {

    private static final FieldRef TITLES = new FieldRef("spu.titles");
    private static final FieldRef LOCALES = new FieldRef("listing.locales");
    private static final FieldRef SKU_SET = new FieldRef("listing.sku_set");

    // --- 类型不符：两条路径（值不是 Map / 不是 List） ---

    @Test
    void stringMap_valueNotAMap_reportsFieldPath() {
        assertThatThrownBy(() -> StepValues.stringMap(contextWith(TITLES, new ArrayList<String>()), TITLES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("spu.titles")
                .hasMessageContaining("Map<String,String>")
                .hasMessageContaining("java.util.ArrayList");
    }

    @Test
    void typedList_valueNotAList_reportsFieldPath() {
        assertThatThrownBy(() -> StepValues.typedList(contextWith(SKU_SET, new HashMap<>()), SKU_SET, ListingSku.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("listing.sku_set")
                .hasMessageContaining("List<ListingSku>")
                .hasMessageContaining("java.util.HashMap");
    }

    /** {@code stringList} 走 {@code typedList(..., String.class)}——同样要带路径。 */
    @Test
    void stringList_valueNotAList_reportsFieldPath() {
        assertThatThrownBy(() -> StepValues.stringList(contextWith(LOCALES, new HashMap<>()), LOCALES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("listing.locales")
                .hasMessageContaining("List<String>");
    }

    // --- 元素类型不符 ---

    @Test
    void stringMap_entryTypeMismatch_reportsFieldPathAndActualKeyValueTypes() {
        Map<Object, Object> bad = new LinkedHashMap<>();
        bad.put(1, "标题");

        assertThatThrownBy(() -> StepValues.stringMap(contextWith(TITLES, bad), TITLES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("spu.titles")
                .hasMessageContaining("键值必须是 String")
                .hasMessageContaining("java.lang.Integer")
                .hasMessageContaining("java.lang.String");
    }

    @Test
    void typedList_elementTypeMismatch_reportsFieldPath() {
        assertThatThrownBy(() -> StepValues.typedList(contextWith(SKU_SET, List.of("sku-1")), SKU_SET, ListingSku.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("listing.sku_set")
                .hasMessageContaining("元素类型须为 ListingSku")
                .hasMessageContaining("java.lang.String");
    }

    @Test
    void typedList_nullElement_reportsFieldPath() {
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);

        assertThatThrownBy(() -> StepValues.typedList(contextWith(SKU_SET, withNull), SKU_SET, ListingSku.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("listing.sku_set")
                .hasMessageContaining("null");
    }

    // --- 缺省语义与正常路径：null → 空集合，正确类型 → 正常转换（本票只改签名与消息，不改语义） ---

    @Test
    void nullValue_yieldsEmptyCollections_insteadOfFailing() {
        assertThat(StepValues.stringMap(contextWith(TITLES, null), TITLES)).isEmpty();
        assertThat(StepValues.stringList(contextWith(LOCALES, null), LOCALES)).isEmpty();
        assertThat(StepValues.typedList(contextWith(SKU_SET, null), SKU_SET, ListingSku.class)).isEmpty();
    }

    @Test
    void wellTypedValues_areConverted() {
        Map<String, String> titles = new LinkedHashMap<>();
        titles.put("zh-CN", "标题");
        var sku = new ListingSku("sku-1", new Money("45.90", "CNY"), true);

        assertThat(StepValues.stringMap(contextWith(TITLES, titles), TITLES))
                .containsExactly(Map.entry("zh-CN", "标题"));
        assertThat(StepValues.stringList(contextWith(LOCALES, List.of("en", "ru")), LOCALES))
                .containsExactly("en", "ru");
        assertThat(StepValues.typedList(contextWith(SKU_SET, List.of(sku)), SKU_SET, ListingSku.class))
                .containsExactly(sku);
    }

    // --- 路径本身非法：不读、直接失败（与 ListingStepContext.path(FieldRef) 同口径） ---

    @Test
    void blankFieldPath_failsBeforeReading() {
        assertThatThrownBy(() -> StepValues.stringMap(contextWith(TITLES, new HashMap<>()), new FieldRef("  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FieldRef.path 必填");
    }

    private static StepContext contextWith(FieldRef field, Object value) {
        Map<String, Object> values = new HashMap<>();
        values.put(field.path(), value);
        return new ReadOnlyStepContext(values);
    }

    /** 手写 fake：只实现 read 的按路径取值；write 直接失败（本测试不涉及写侧）。 */
    private static final class ReadOnlyStepContext implements StepContext {

        private final Map<String, Object> values;

        private ReadOnlyStepContext(Map<String, Object> values) {
            this.values = values;
        }

        @Override
        public JsonNode params() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public Object read(FieldRef field) {
            return values.get(field.path());
        }

        @Override
        public void write(FieldRef field, Object value) {
            throw new UnsupportedOperationException("本 fake 只服务 StepValues 的读侧测试");
        }
    }
}
