package io.autocommerce.catalog.store;

import io.autocommerce.core.catalog.model.ProductCatalog;

import java.util.List;
import java.util.Optional;

/**
 * catalog master 文档库端口（#19 决议：v1 落库形态 = CatalogStore 端口 + schema 校验 JSON 文档库）。
 *
 * <p>存储单元 = 单个 SPU 的 product 文档（{@link ProductCatalog}，spus() 恰含 1 条 + 其 SKU /
 * MediaAsset 全量；文档形态与 product-catalog.schema.json 一致，是"master 数据通过 schema 契约
 * 校验"的落地点）。真库（Postgres/JPA）表结构属设计期未决区，留待装配点单独收敛——届时以
 * 同一端口换实现，消费方（ingest / 后续 content、publish slice）不感知。
 *
 * <p>语义：以 spuId 为键；同 spuId 重复 put = 幂等覆盖（重新采集同 offer → 更新 master，
 * 不产生重复记录）。
 */
public interface CatalogStore {

    /** 持久化单个 SPU 的 product 文档（spus() 恰 1 条；否则为调用方 bug）。 */
    void put(ProductCatalog product);

    /** 按 spuId 取回 product 文档；不存在返回 empty。 */
    Optional<ProductCatalog> get(String spuId);

    /** 列出全部 spuId（demo / 看板 seed 用）。 */
    List<String> listSpuIds();
}
