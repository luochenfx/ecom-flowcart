package io.autocommerce.catalog.store;

import io.autocommerce.core.catalog.model.ProductCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CatalogStore 端口契约测试（两个实现共跑同一套断言）。
 *
 * <p>聚合 {@link CatalogStore} 端口的全部行为契约：put/get roundtrip（含嵌套集合 / 可空字段保真）、
 * 缺档 empty、同 spuId 重复 put 幂等覆盖、多 SPU 文档拒绝（{@code requireSingleSpu}）、
 * listSpuIds 排序与空库。实现方只需提供 {@link #newStore()}。
 *
 * <p>与存储层绑定的断言（如 Postgres DB CHECK 约束）不在此基类——它们在各自实现测试中单独声明
 * （JSON 实现没有 DB 约束）。
 */
abstract class CatalogStoreContractTest {

    /** 供每个测试使用的全新空 store（实现方保证空库 / 空目录起点）。 */
    protected abstract CatalogStore newStore();

    @Test
    void putAndGetRoundTripMinimal() {
        CatalogStore store = newStore();
        ProductCatalog doc = CatalogTestDocs.singleSpuDocument("spu-1688-6688990011");

        store.put(doc);

        assertThat(store.get("spu-1688-6688990011")).isPresent().get().isEqualTo(doc);
        assertThat(store.listSpuIds()).containsExactly("spu-1688-6688990011");
    }

    @Test
    void putAndGetRoundTripPreservesNestedCollectionsAndNullableFields() {
        CatalogStore store = newStore();
        ProductCatalog doc = CatalogTestDocs.richSingleSpuDocument("spu-rich-1");

        store.put(doc);
        ProductCatalog fetched = store.get("spu-rich-1").orElseThrow();

        // 落库再取回必须等值（嵌套 images / skus / attributes / 可空字段全保真）
        assertThat(fetched).isEqualTo(doc);
        assertThat(fetched.spus().getFirst().titles())
                .containsEntry("en-US", "Portable Bluetooth Speaker");
        assertThat(fetched.spus().getFirst().platformRaw()).isNull();
        assertThat(fetched.skus().getFirst().specs()).extracting("name").containsExactly("容量");
    }

    @Test
    void getMissingReturnsEmpty() {
        CatalogStore store = newStore();
        assertThat(store.get("spu-1688-1")).isEmpty();
        assertThat(store.listSpuIds()).isEmpty();
    }

    @Test
    void putSameSpuOverwritesIdempotently() {
        CatalogStore store = newStore();
        store.put(CatalogTestDocs.singleSpuDocument("spu-1"));
        store.put(CatalogTestDocs.singleSpuDocument("spu-1"));

        assertThat(store.listSpuIds()).containsExactly("spu-1");
    }

    @Test
    void putSameSpuOverwriteReplacesContent() {
        CatalogStore store = newStore();
        store.put(CatalogTestDocs.singleSpuDocument("spu-1"));
        store.put(CatalogTestDocs.richSingleSpuDocument("spu-1"));

        assertThat(store.get("spu-1")).isPresent()
                .get().isEqualTo(CatalogTestDocs.richSingleSpuDocument("spu-1"));
        assertThat(store.listSpuIds()).containsExactly("spu-1");
    }

    @Test
    void rejectsDocumentWithMultipleSpus() {
        CatalogStore store = newStore();
        ProductCatalog twoSpus = new ProductCatalog("0.1.0",
                List.of(
                        CatalogTestDocs.singleSpuDocument("spu-1").spus().getFirst(),
                        CatalogTestDocs.singleSpuDocument("spu-2").spus().getFirst()),
                List.of(), List.of(), List.of());

        assertThatThrownBy(() -> store.put(twoSpus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("恰 1 条");
    }

    @Test
    void listSpuIdsIsSorted() {
        CatalogStore store = newStore();
        store.put(CatalogTestDocs.singleSpuDocument("spu-c"));
        store.put(CatalogTestDocs.singleSpuDocument("spu-a"));
        store.put(CatalogTestDocs.singleSpuDocument("spu-b"));

        assertThat(store.listSpuIds()).containsExactly("spu-a", "spu-b", "spu-c");
    }
}
