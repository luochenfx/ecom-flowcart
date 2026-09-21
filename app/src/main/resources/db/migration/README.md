# Flyway 迁移脚本目录

数据库版本控制由 Flyway 承担（随首个落库 slice 引入 `flyway-core` 依赖后自动扫描本目录）。

命名约定：`V{version}__{description}.sql`（如 `V1__catalog.sql`、`V2__add_order_table.sql`）。
`{version}` 为单段整数版本号，按序递增（`V1` < `V2` < `V10`，按数值比较）；点号形态（如 `V1.0`）为 Flyway 合法语法，本仓为可读性统一约定使用单段整数。
多模块共用同一 `flowcart` 业务库：schema 演进脚本统一收口在本目录（模块化单体集中演进点），
版本号单调递增、只增不改；已发布的脚本禁止原地编辑（回滚 = 新脚本补偿）。
