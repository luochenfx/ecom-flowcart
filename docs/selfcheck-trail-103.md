# 自守检查线上自举探针（issue #103，一次性）

本文件仅用于观测 `Guard Self-Check` 在三段式 trail 上的行为，采集完证据后随分支一并删除，不合并。

阶段① 正向绿基线：base（main=`a141e8d`）已含修复版 `guard-selfcheck.yml`；本 PR 未触碰 `ci.yml`，期望 `Guard Self-Check` = **pass**。
