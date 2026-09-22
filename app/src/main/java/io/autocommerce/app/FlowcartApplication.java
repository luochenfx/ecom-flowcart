package io.autocommerce.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ecom-flowcart 聚合装配入口（模块化单体，ADR-0009）。
 *
 * <p>单一 artifact 启动时可切 {@code --app.role=api|worker|scheduler}（单机合一）；本票（#74）落地
 * 基础设施装配（Postgres / Flyway / Temporal 真 server）、各链 launcher / 服务 bean，以及按 role
 * 启停 worker（见 {@link WorkerRoleCondition} / {@link WorkerStartup}）。
 *
 * <h2>为什么必须显式 {@code scanBasePackages = "io.autocommerce"}</h2>
 * 本类位于 {@code io.autocommerce.app}。Spring Boot 默认以启动类所在包为组件扫描根，<b>扫不到</b>
 * {@code io.autocommerce.api}（{@code FlowController} / {@code ApiJacksonConfiguration}）与各业务
 * 模块——后果是 REST 端点在<b>生产装配下静默缺失</b>（而单测可能仍绿，最危险的失败形态；#73 实证）。
 * 故把扫描根上提到公共前缀 {@code io.autocommerce}，覆盖 api + 各内部模块的 Spring 组件。
 *
 * <p>业务模块（catalog / content / publish / order / projection）**零** Spring 注解（ArchUnit 守门），
 * 扫描它们不会引入额外 bean；扫描只对 {@code app} / {@code api} 的组件生效。
 */
@SpringBootApplication(scanBasePackages = "io.autocommerce")
@ConfigurationPropertiesScan("io.autocommerce.app")
public class FlowcartApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowcartApplication.class, args);
    }
}
