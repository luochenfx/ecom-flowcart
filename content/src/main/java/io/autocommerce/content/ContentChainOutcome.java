package io.autocommerce.content;

import io.autocommerce.core.catalog.model.ProductCatalog;

/**
 * 内容链一次编排的产出：执行结果（含执行记录/降级留痕）+ 物化后的文档。
 * 落库由调用方负责（进程内调用方 / Temporal activity），内容链本身不做 IO。
 */
public record ContentChainOutcome(ContentChainResult result, ProductCatalog document) {
}
