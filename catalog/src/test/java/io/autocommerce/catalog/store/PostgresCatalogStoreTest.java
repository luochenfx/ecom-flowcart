package io.autocommerce.catalog.store;

import io.autocommerce.core.catalog.model.ProductCatalog;
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
 *       直接写 SQL 也会被存储层拒绝（应用层 requireSingleSpu 与存储层 CHECK 是两道独立的门）。
 *       三种不合规形态都覆盖：值 ≠ 0.1.0、缺 {@code schema_version} 键、值为 JSON null。</li>
 *   <li>{@code updated_at} 写入语义：同 spuId 覆盖时 {@code created_at} 不变、{@code updated_at}
 *       前移（spec §8.2「显式写 updated_at、不用触发器」的护栏）。</li>
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
    void databaseConstraintRejectsMissingSchemaVersion() {
        // AC 5 强化：schema_version 是 schema 的 required 字段——「键缺失」也是不合规文档，
        // 存储层必须拒。旧写法 `doc ->> 'schema_version' = '0.1.0'` 在三值逻辑下会放过它（NULL 视为通过）。
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO catalog_product (spu_id, doc)
                VALUES (?, CAST(? AS jsonb))
                """, "spu-missing", "{\"spus\":[]}"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void databaseConstraintRejectsExplicitNullSchemaVersion() {
        // AC 5 强化：`{"schema_version":null}` 键存在但值为 JSON null，经 ->> 仍是 SQL NULL，
        // 旧写法同样放过。存储层必须拒。
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO catalog_product (spu_id, doc)
                VALUES (?, CAST(? AS jsonb))
                """, "spu-null", "{\"schema_version\":null}"))
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
    void putSameSpuOverwriteAdvancesUpdatedAtButKeepsCreatedAt() throws Exception {
        // spec §8.2：updated_at 由 PostgresCatalogStore 显式写入（不用触发器）。
        // 锁住「同 spuId 覆盖时 created_at 不变、updated_at 前移」——防 SET 子句未来漏写
        // updated_at 或误加 created_at = now() 而全体测试仍绿。
        CatalogStore store = newStore();
        ProductCatalog first = CatalogTestDocs.singleSpuDocument("spu-ts-1");
        store.put(first);

        java.sql.Timestamp createdBefore = jdbc.queryForObject(
                "SELECT created_at FROM catalog_product WHERE spu_id = ?",
                java.sql.Timestamp.class, spuIdOf(first));
        java.sql.Timestamp updatedBefore = jdbc.queryForObject(
                "SELECT updated_at FROM catalog_product WHERE spu_id = ?",
                java.sql.Timestamp.class, spuIdOf(first));

        // 两次 put 各占一个自动提交连接（不同事务）；now() 是事务开始时间，隔开才能分辨。
        Thread.sleep(50);
        store.put(first);

        java.sql.Timestamp createdAfter = jdbc.queryForObject(
                "SELECT created_at FROM catalog_product WHERE spu_id = ?",
                java.sql.Timestamp.class, spuIdOf(first));
        java.sql.Timestamp updatedAfter = jdbc.queryForObject(
                "SELECT updated_at FROM catalog_product WHERE spu_id = ?",
                java.sql.Timestamp.class, spuIdOf(first));

        assertThat(createdAfter).isEqualTo(createdBefore);   // 覆盖不改 created_at
        assertThat(updatedAfter).isAfter(updatedBefore);     // updated_at 严格前移
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

    private static String spuIdOf(ProductCatalog product) {
        return product.spus().getFirst().spuId();
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
