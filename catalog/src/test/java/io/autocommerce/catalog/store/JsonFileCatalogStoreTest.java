package io.autocommerce.catalog.store;

import io.autocommerce.core.catalog.model.ProductCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JsonFileCatalogStore：v1 落库形态（schema 校验 JSON 文档库，按 SPU 一文件）——put/get
 * roundtrip、缺档 empty、幂等覆盖、spuIds 列表、单 SPU 文档约束。
 */
class JsonFileCatalogStoreTest {

    @TempDir
    Path tmp;

    @Test
    void putAndGetRoundTrip() {
        JsonFileCatalogStore store = new JsonFileCatalogStore(tmp.resolve("docs"));
        ProductCatalog doc = CatalogTestDocs.singleSpuDocument("spu-1688-6688990011");

        store.put(doc);

        assertThat(store.get("spu-1688-6688990011")).isPresent()
                .get().isEqualTo(doc);
        assertThat(store.listSpuIds()).containsExactly("spu-1688-6688990011");
        assertThat(Files.isRegularFile(tmp.resolve("docs/spu-1688-6688990011.json"))).isTrue();
    }

    @Test
    void getMissingReturnsEmpty() {
        JsonFileCatalogStore store = new JsonFileCatalogStore(tmp.resolve("empty"));
        assertThat(store.get("spu-1688-1")).isEmpty();
        assertThat(store.listSpuIds()).isEmpty();
    }

    @Test
    void putSameSpuOverwritesIdempotently() {
        JsonFileCatalogStore store = new JsonFileCatalogStore(tmp.resolve("docs"));
        store.put(CatalogTestDocs.singleSpuDocument("spu-1"));
        store.put(CatalogTestDocs.singleSpuDocument("spu-1"));

        assertThat(store.listSpuIds()).containsExactly("spu-1");
    }

    @Test
    void rejectsDocumentWithMultipleSpus() {
        JsonFileCatalogStore store = new JsonFileCatalogStore(tmp.resolve("docs"));
        ProductCatalog twoSpus = new ProductCatalog("0.1.0",
                java.util.List.of(
                        CatalogTestDocs.singleSpuDocument("spu-1").spus().get(0),
                        CatalogTestDocs.singleSpuDocument("spu-2").spus().get(0)),
                java.util.List.of(), java.util.List.of(), java.util.List.of());

        assertThatThrownBy(() -> store.put(twoSpus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("恰 1 条");
    }
}
