package io.autocommerce.core.step;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Step 执行上下文（specs/0006 §3 StepContext）。读标准模型字段 → 处理 → 写回标准模型字段；
 * 无中间 DTO 流。字段路径解析与事务边界由 content slice（Temporal workflow 内）实现，
 * core 只定义契约语义。
 */
public interface StepContext {

    /** 步骤参数（温度/指令模板/加价公式…，来自 StepDescriptor.params 与运行期覆盖） */
    JsonNode params();

    /** 读标准模型字段（如 read(FieldRef("listing.title"))） */
    Object read(FieldRef field);

    /** 写回标准模型字段（Step 产物落库时标记 provenance.step） */
    void write(FieldRef field, Object value);
}
