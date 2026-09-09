package io.autocommerce.core.step;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.List;

/**
 * Step 描述（specs/0006 §3 StepDescriptor）。params 为步骤参数（温度/指令模板/加价公式…），
 * JsonNode 表达（具体 schema 形态属实现期 fog，specs/0006 §10）。产物写库标记 provenance.step。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StepDescriptor(
        String id,
        List<FieldRef> input,
        List<FieldRef> output,
        ModelRequirement modelRequirement,
        JsonNode params) {
}
