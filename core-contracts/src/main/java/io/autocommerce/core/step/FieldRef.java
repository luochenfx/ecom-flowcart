package io.autocommerce.core.step;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * 标准模型字段引用（specs/0006 §3 FieldRef）。路径形态 = 模型层级 + 字段，如 "listing.title"
 * / "spu.title_i18n" / "media[]"。Step 边界 = 标准模型字段级读写：input = 读哪些字段、
 * output = 写哪些字段；不发明中间 DTO 流。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FieldRef(String path) {
}
