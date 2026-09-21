package io.autocommerce.catalog.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgresCatalogStore：CatalogStore 的 Postgres 真库实现。
 *
 * <p>端口行为契约由 {@link CatalogStoreContractTest} 共跑（与 JSON 实现同一套断言）；本类另补
 * Postgres 专属断言：
 * <ul>
 *   <li>DB CHECK 约束（{@code catalog_product_schema_version}）拒绝不合规文档——<b>绕过应用层</b>
 *       直接写 SQL 也会被存储层拒绝（应用层 requireSingleSpu 与存储层 CHECK 是两道独立的门）。</li>
 *   <li>migration DDL 无漂移：catalog 测试用 schema 与 app 生产迁移脚本 DDL 一致。</li>
 * </ul>
 *
 * <p>真实容器由 Testcontainers 提供；无 Docker 环境（如本机未启 / CI 未配）时
 * {@code disabledWithoutDocker = true} 使全部用例优雅跳过，因此 {@code mvn clean test} 不强制依赖
 * Docker（spec §9.2：CI 只跑 {@code mvn clean test}）。
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresCatalogStoreTest extends CatalogStoreContractTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        if (dataSource == null) {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setURL(POSTGRES.getJdbcUrl());
            ds.setUser(POSTGRES.getUsername());
            ds.setPassword(POSTGRES.getPassword());
            try (Connection c = ds.getConnection()) {
                ScriptUtils.executeSqlScript(c, new org.springframework.core.io.FileSystemResource(
                        "src/test/resources/catalog-schema.sql"));
            }
            dataSource = ds;
            jdbc = new JdbcTemplate(ds);
        }
        // 每个测试独立空库起点（契约测试要求）
        jdbc.update("TRUNCATE catalog_product");
    }

    @Override
    protected CatalogStore newStore() {
        return new PostgresCatalogStore(jdbc);
    }

    @Test
    void databaseConstraintRejectsNonCompliantSchemaVersion() {
        // 绕过应用层（requireSingleSpu）与序列化，直接写 SQL —— 存储层 CHECK 必须拒绝。
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO catalog_product (spu_id, doc)
                VALUES (?, CAST(? AS jsonb))
                """, "spu-bad", "{\"schema_version\":\"9.9.9\"}"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void databaseConstraintAcceptsCompliantSchemaVersion() {
        jdbc.update("""
                INSERT INTO catalog_product (spu_id, doc)
                VALUES (?, CAST(? AS jsonb))
                """, "spu-ok", "{\"schema_version\":\"0.1.0\"}");

        assertThat(jdbc.queryForList("SELECT spu_id FROM catalog_product", String.class))
                .containsExactly("spu-ok");
    }

    @Test
    void schemaMirrorsAppMigration() throws Exception {
        String migration = Files.readString(
                Path.of("../app/src/main/resources/db/migration/V1__catalog.sql"));
        String testSchema = Files.readString(
                Path.of("src/test/resources/catalog-schema.sql"));

        // 抽取 DDL 语句（去注释、去空行）逐条比对，防测试 schema 与生产迁移漂移。
        assertThat(ddlStatements(testSchema)).isEqualTo(ddlStatements(migration));
    }

    private static java.util.List<String> ddlStatements(String sql) {
        return java.util.Arrays.stream(sql.split(";"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> s.lines()
                        .map(String::strip)
                        .filter(l -> !l.startsWith("--") && !l.isEmpty())
                        .reduce((a, b) -> a + "\n" + b).orElse(""))
                .map(s -> s.replaceAll("\\s+", " ").strip())
                .toList();
    }
}
