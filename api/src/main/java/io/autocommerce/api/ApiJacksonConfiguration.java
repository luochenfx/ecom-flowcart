package io.autocommerce.api;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.PropertyNamingStrategies;

/**
 * api 的 REST 载荷命名约定：**snake_case**（specs/0007 §6.2，与既有 schema 一致）。
 *
 * <h2>① 为何必须在此统一设置全局 SNAKE_CASE</h2>
 * Spring Boot 4 的 HTTP 消息转换走 **Jackson 3**（{@code tools.jackson.*}）。而核心模型
 * （{@code SourceRef} / {@code CategoryRef} / {@code DegradedStep}）用的是 **Jackson 2** 的
 * {@code com.fasterxml.jackson.databind.annotation.JsonNaming}（databind 注解）——Jackson 3 不识别它
 * （databind 注解已迁到 {@code tools.jackson.databind.annotation}），且 core 冻结、不宜逐个改。故这里
 * 对 HTTP {@code JsonMapper} 统一设置全局命名策略 = SNAKE_CASE，使**所有** REST 载荷（含内嵌核心记录）
 * 自动 snake_case。若缺此设置，HTTP 层对核心记录会拿到**全 null 字段**（裸 ObjectMapper 走 Jackson 3
 * 时 {@code @JsonNaming} 失效、属性名匹配不上）——故这不是可选优化，而是载荷正确性的前提。
 *
 * <h2>② 作用域边界</h2>
 * 只作用于 **HTTP 层的 {@code JsonMapper}**（Spring MVC 消息转换）。它**不污染** core 的
 * {@code ContractObjectMapper}，也不影响各 store 自建 / 复用的 mapper——那些 mapper 各自按需配置，
 * 不读本 Bean。即「命名策略只改出口（HTTP 载荷），不改内核序列化」。
 *
 * <h2>③ 刻意不启用 NON_NULL（design decision）</h2>
 * 本 mapper **不**加入 {@code SerializationFeature.NON_NULL}：{@code GET /api/v1/flows/{workflowId}} 在
 * {@code RUNNING} 态需要**显式 null 的 {@code result} 字段**来表达「尚无结果」这一有意义语义。若启用
 * NON_NULL，null 字段会被整段吞掉，调用方将无法区分「字段被吞（配置问题）」与「确无结果（链路还在跑）」
 * ——这正是本端点的核心可观测语义（见 {@link FlowQueryResponse}）。错误体同理依赖显式 null 的
 * {@code platform_code} / {@code flow_workflow_id} 来保持响应形状稳定。
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
