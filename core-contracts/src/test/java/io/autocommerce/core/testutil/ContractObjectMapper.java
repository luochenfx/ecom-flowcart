package io.autocommerce.core.testutil;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 契约测试共享 ObjectMapper（随 test-jar 分发，供所有模块的 schema↔Java 双向 fixture 复用）。
 * <ul>
 *   <li>SNAKE_CASE：模型 camelCase 字段 ↔ schema snake_case 键，无需逐字段注解；</li>
 *   <li>NON_NULL：可选/nullable 字段缺省时省略输出（schema 视为可省略，语义等价）；</li>
 *   <li>date-time / 枚举等均为 String / enum 直出，无需 JavaTimeModule。</li>
 * </ul>
 */
public final class ContractObjectMapper {

    private ContractObjectMapper() {
    }

    public static ObjectMapper create() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        // 契约测试禁止时间戳数字形态；String 时间原样输出
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
