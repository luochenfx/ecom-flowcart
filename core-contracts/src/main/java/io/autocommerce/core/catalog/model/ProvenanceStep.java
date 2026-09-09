package io.autocommerce.core.catalog.model;

/**
 * 对象级血缘步骤（schema: Provenance.created_by_step / updated_by_step enum）。
 * 对象级 provenance 承载审计回溯；不做字段级 lineage。
 */
public enum ProvenanceStep {
    CAPTURE,
    CLEAN,
    AI,
    LISTING,
    RECONCILE,
    HUMAN
}
