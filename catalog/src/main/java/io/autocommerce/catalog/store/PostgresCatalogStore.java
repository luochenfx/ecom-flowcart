package io.autocommerce.catalog.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.autocommerce.core.catalog.model.ProductCatalog;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * PostgresCatalogStore —— CatalogStore 的 Postgres 真库实现（specs/0007 §8）。
 *
 * <p>与 {@link JsonFileCatalogStore} 同端口（{@link CatalogStore} 签名未改），证明持久化层可在
 * 不改端口、不改任何业务模块的前提下替换。文档整列（一个 text 主键 + 一个 jsonb 列）落
 * {@code catalog_product} 表：
 *
 * <ul>
 *   <li>{@code put}：单条 {@code INSERT ... ON CONFLICT (spu_id) DO UPDATE} —— 与 JSON 实现的
 *       "同 spuId 重复 put = 幂等覆盖"语义对齐；无需显式事务；{@code updated_at} 由本实现显式写入
 *       （不用触发器 —— 保持"业务模块不知道存储细节"的对称性）。</li>
 *   <li>{@code get}：按主键取 doc，反序列化为 {@link ProductCatalog}；不存在返回
 *       {@link Optional#empty()}。</li>
 *   <li>{@code listSpuIds}：只 {@code SELECT spu_id} —— 不依赖对 doc 内容的查询。</li>
 * </ul>
 *
 * <p><b>为何用 JdbcTemplate 而非 JPA</b>：CatalogStore 操作的是 {@code doc} 整列，没有关系建模
 * （一个 text 主键 + 一个 jsonb 列），JPA 的实体映射与 {@code AttributeConverter} 在此收益为零、
 * 配置成本非零，故取 {@code JdbcTemplate}。
 *
 * <p>序列化沿用 {@link JsonFileCatalogStore} 的既有 Jackson 配置（由 {@link CatalogDocJson} 单一
 * 事实源提供：snake_case、NON_NULL）；文档形态与 JSON 实现一致，仅去掉文件实现才需要的缩进输出。
 *
 * <p>写路径不做运行时 schema 校验（与 JSON 实现一致）——校验门由 DB CHECK 约束
 * （{@code catalog_product_schema_version}）与端口契约测试共同承担。
 */
public final class PostgresCatalogStore implements CatalogStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = CatalogDocJson.newMapper();

    public PostgresCatalogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void put(ProductCatalog product) {
        requireSingleSpu(product);
        jdbc.update("""
                INSERT INTO catalog_product (spu_id, doc, created_at, updated_at)
                VALUES (?, CAST(? AS jsonb), now(), now())
                ON CONFLICT (spu_id) DO UPDATE
                    SET doc = EXCLUDED.doc, updated_at = now()
                """, spuId(product), CatalogDocJson.write(mapper, product));
    }

    @Override
    public Optional<ProductCatalog> get(String spuId) {
        List<String> docs = jdbc.queryForList(
                "SELECT doc FROM catalog_product WHERE spu_id = ?", String.class, spuId);
        if (docs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(CatalogDocJson.read(mapper, docs.getFirst()));
    }

    @Override
    public List<String> listSpuIds() {
        return jdbc.queryForList(
                "SELECT spu_id FROM catalog_product ORDER BY spu_id", String.class);
    }

    private static String spuId(ProductCatalog product) {
        return product.spus().getFirst().spuId();
    }

    private static void requireSingleSpu(ProductCatalog product) {
        if (product.spus() == null || product.spus().size() != 1) {
            throw new IllegalArgumentException(
                    "CatalogStore 存储单元 = 单个 SPU 的 product 文档，spus() 必须恰 1 条");
        }
    }
}
