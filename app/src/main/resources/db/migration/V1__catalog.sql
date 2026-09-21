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

-- 不合规文档写不进去（schema_version 是 product-catalog.schema.json 的 required + const）。
-- 存储层校验门：绕过应用层直接写 SQL 也会被 DB 拒绝。
--
-- 为何不能只用 `doc ->> 'schema_version' = '0.1.0'`（spec §8.2 的写法）：SQL 三值逻辑下，
-- 该表达式会放过三种不合规文档 ——
--   1) 缺 schema_version 键：`doc ->> 'schema_version'` 返回 NULL，`NULL = '0.1.0'` 求值为 NULL，
--      CHECK 视 NULL 为通过 → 被接受（而它是 schema 的 required 字段）；
--   2) 显式 `{"schema_version":null}`：JSON null 经 `->>` 抽出仍是 SQL NULL，同上 → 被接受；
--   3) 单靠 `doc ? 'schema_version'` 判键存在也不够：键在但值为 null 时 `?` 返回 true，
--      而 `->>` 仍给 NULL → 仍被接受。
-- 因此用两项合取：`doc ? 'schema_version'` 保证键存在，`doc -> 'schema_version' = '"0.1.0"'::jsonb`
-- 直接比 jsonb 值（不做文本抽取），键缺失与值为 null/数字/数组都会落到「不相等」而非 NULL。
-- 经真实 Postgres 16 实测：仅 {"schema_version":"0.1.0"} 被接受，
-- 缺键 / null / "9.9.9" / 0.1(数字) / ["0.1.0"](数组) 全部被拒。
ALTER TABLE catalog_product
    ADD CONSTRAINT catalog_product_schema_version
    CHECK (doc ? 'schema_version' AND doc -> 'schema_version' = '"0.1.0"'::jsonb);
