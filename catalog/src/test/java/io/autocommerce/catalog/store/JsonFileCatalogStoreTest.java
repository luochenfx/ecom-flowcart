package io.autocommerce.catalog.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JsonFileCatalogStore：v1 落库形态（schema 校验 JSON 文档库，按 SPU 一文件）。
 *
 * <p>端口行为契约由 {@link CatalogStoreContractTest} 共跑（与 Postgres 实现同一套断言）；
 * 本类只补 JSON 文件形态专属断言（每 SPU 一文件落盘）。
 */
class JsonFileCatalogStoreTest extends CatalogStoreContractTest {

    @TempDir
    Path tmp;

    @Override
    protected CatalogStore newStore() {
        return new JsonFileCatalogStore(tmp.resolve("docs-" + System.nanoTime()));
    }

    @Test
    void writesOneJsonFilePerSpu() {
        JsonFileCatalogStore store = new JsonFileCatalogStore(tmp.resolve("docs"));
        store.put(CatalogTestDocs.singleSpuDocument("spu-1688-6688990011"));

        assertThat(Files.isRegularFile(tmp.resolve("docs/spu-1688-6688990011.json"))).isTrue();
    }
}
