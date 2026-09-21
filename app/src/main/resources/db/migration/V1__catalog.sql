-- V1__catalog.sql —— catalog master 文档库落 Postgres（specs/0007 §8.2）。
--
-- 单表 + JSONB 文档列：存储单元 = 单个 SPU 的 product 文档（doc 整列即 product-catalog 文档）。
-- 不加 GIN 索引（v1 无按 doc 内容查询的需求，listSpuIds() 只需扫主键）；
-- 不加来源平台列（平台信息已在 doc 内，加列即第二真相源）。
-- created_at 成本为零、未来排查需要；updated_at 由 PostgresCatalogStore 显式写入（不用触发器 ——
-- 保持"业务模块不知道存储细节"的对称性）。

CREATE TABLE catalog_product (
    spu_id     text        PRIMARY KEY,
    doc        jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 不合规文档写不进去（schema_version 是 product-catalog.schema.json 的 const）。
-- 存储层校验门：绕过应用层直接写 SQL 也会被 DB 拒绝。
ALTER TABLE catalog_product
    ADD CONSTRAINT catalog_product_schema_version
    CHECK (doc ->> 'schema_version' = '0.1.0');
