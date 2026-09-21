-- catalog 端口契约测试用 schema。
--
-- 与生产迁移脚本 app/src/main/resources/db/migration/V1__catalog.sql 的 DDL 必须一致
-- （catalog 模块保持零 SQL——Flyway 只在 app 跑；此文件仅 test scope，供 Testcontainers 建表）。
-- PostgresCatalogStoreTest#schemaMirrorsAppMigration 断言二者无漂移。
CREATE TABLE catalog_product (
    spu_id     text        PRIMARY KEY,
    doc        jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE catalog_product
    ADD CONSTRAINT catalog_product_schema_version
    CHECK (doc ? 'schema_version' AND doc -> 'schema_version' = '"0.1.0"'::jsonb);
