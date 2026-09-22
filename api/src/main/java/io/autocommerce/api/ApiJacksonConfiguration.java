package io.autocommerce.api;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.PropertyNamingStrategies;

/**
 * api 的 REST 载荷命名约定：**snake_case**（specs/0007 §6.2，与既有 schema 一致）。
 *
 * <p><b>为何在 api 侧统一设置</b>：Spring Boot 4 的 HTTP 消息转换走 **Jackson 3**（{@code tools.jackson.*}）。
 * 而核心模型（{@code SourceRef} / {@code CategoryRef} / {@code DegradedStep}）用的是 **Jackson 2** 的
 * {@code com.fasterxml.jackson.databind.annotation.JsonNaming}（databind 注解）——Jackson 3 不识别它
 * （databind 注解已迁到 {@code tools.jackson.databind.annotation}），且 core 冻结、不宜逐个改。
 * 故这里对 HTTP {@code JsonMapper} 统一设置全局命名策略 = SNAKE_CASE，使**所有** REST 载荷
 * （含内嵌核心记录）自动 snake_case。
 *
 * <p>注：{@code com.fasterxml.jackson.annotation}（jackson-annotations，如 {@code @JsonCreator} /
 * {@code @JsonValue}）Jackson 3 仍识别——{@link ChainKind} 的线上值映射因此不受影响。
 */
@Configuration
public class ApiJacksonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer snakeCaseJsonNaming() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
