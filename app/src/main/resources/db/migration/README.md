# Flyway 迁移脚本目录

数据库版本控制由 Flyway 承担（随首个落库 slice 引入 `flyway-core` 依赖后自动扫描本目录）。

命名约定：`V{major}.{minor}__{description}.sql`（如 `V1.0__init_catalog_schema.sql`）。
多模块共用同一 `flowcart` 业务库：schema 演进脚本统一收口在本目录（模块化单体集中演进点），
版本号单调递增、只增不改；已发布的脚本禁止原地编辑（回滚 = 新脚本补偿）。
