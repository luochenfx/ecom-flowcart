# Flyway 迁移脚本目录

数据库版本控制由 Flyway 承担（随首个落库 slice 引入 `flyway-core` 依赖后自动扫描本目录）。

命名约定：`V{version}__{description}.sql`（如 `V1__catalog.sql`、`V2__add_order_table.sql`）。
`{version}` 为单段整数版本号，按序递增（`V1` < `V2` < `V10`，按数值比较）；点号形态（如 `V1.0`）为 Flyway 合法语法，本仓为可读性统一约定使用单段整数。
多模块共用同一 `flowcart` 业务库：schema 演进脚本统一收口在本目录（模块化单体集中演进点），
版本号单调递增、只增不改；已发布的脚本禁止原地编辑（回滚 = 新脚本补偿）。

## `V1__catalog.sql` 的实际内容

V1 把 **catalog master 文档库**落 Postgres（规范 [0007 §8.2](../../../../../../docs/specs/0007-end-to-end-flow-assembly.md)）——
**只有 catalog 落真库**，`OrderStore` / `PublishStateStore` 仍为 JSON 文件实现（§8.4 / §8.5）。
脚本做两件事：

1. **建表**（单表 + JSONB 文档列；存储单元 = 单个 SPU 的 product 文档）：

   ```sql
   CREATE TABLE catalog_product (
       spu_id     text        PRIMARY KEY,
       doc        jsonb       NOT NULL,
       created_at timestamptz NOT NULL DEFAULT now(),
       updated_at timestamptz NOT NULL DEFAULT now()
   );
   ```

2. **加 CHECK 约束** `catalog_product_schema_version`——不合规文档写不进去
   （`schema_version` 是 `product-catalog.schema.json` 的 required + const）：

   ```sql
   ALTER TABLE catalog_product
       ADD CONSTRAINT catalog_product_schema_version
       CHECK (doc ? 'schema_version' AND doc -> 'schema_version' = '"0.1.0"'::jsonb);
   ```

   为何不用 `doc ->> 'schema_version' = '0.1.0'`（规范 §8.2 的写法）：SQL 三值逻辑下该表达式会
   放过缺键 / `null` 等不合规文档（`NULL = '0.1.0'` 求值为 NULL，CHECK 视 NULL 为通过）。故用
   两项合取：`doc ? 'schema_version'` 保证键存在，`doc -> 'schema_version' = '"0.1.0"'::jsonb`
   直接比 jsonb 值（不做文本抽取）。**JSONB 判等禁 `->>`，三值逻辑会放行。**

**其余存储决策**：不加 GIN 索引（v1 无按 doc 内容查询需求，`listSpuIds()` 只需扫主键）；
不加来源平台列（平台信息已在 `doc` 内，加列即第二真相源）；`updated_at` 由
`PostgresCatalogStore` 显式写入，**不用触发器**（保持"业务模块不知道存储细节"的对称性）。
