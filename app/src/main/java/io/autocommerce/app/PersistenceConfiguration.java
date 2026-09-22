package io.autocommerce.app;

import io.autocommerce.catalog.store.CatalogStore;
import io.autocommerce.catalog.store.PostgresCatalogStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 持久化装配（specs/0007 §7.2/§8）：catalog master 文档库落 Postgres。
 *
 * <p>{@link JdbcTemplate} 由 Spring Boot 的 JDBC 自动配置提供（datasource 见 {@code application.yml}
 * 的 {@code spring.datasource}）。本类把端口 {@link CatalogStore} 绑到真库实现
 * {@link PostgresCatalogStore}——业务模块（catalog）不知道装配选择，换实现不改端口
 * （specs/0007 §8.1/§8.4）。
 *
 * <p><b>Flyway 只在 app 跑</b>（specs/0007 §8.4）：脚本源 {@code classpath:db/migration}
 * （{@code V1__catalog.sql}）由自动配置在启动时迁移，catalog 模块保持零 SQL。
 */
@Configuration
public class PersistenceConfiguration {

    /** catalog 文档库端口 → Postgres 真库实现（装配点选择，specs/0007 §8.4）。 */
    @Bean
    CatalogStore catalogStore(JdbcTemplate jdbcTemplate) {
        return new PostgresCatalogStore(jdbcTemplate);
    }
}
