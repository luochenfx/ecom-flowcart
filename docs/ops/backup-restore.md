# 备份 / 恢复 SOP —— postgres 双库 pg_dump

> 单一权威来源：本文件定义 ecom-flowcart 各基础设施容器的**状态归类**、**备份对象**、**备份语义**与**恢复流程**。
> 散落于 compose / architecture.md / ADR 的备份措辞以此为准（已同步口径，见文末"口径纠偏"）。
> 配套可执行脚本骨架：`docker/backup-postgres.sh`（备份）、`docker/restore-postgres.sh`（恢复演练）。

## 1. 状态归类（先答"要备什么、丢了意味着什么"）

三个基础设施容器中，**只有 RabbitMQ 可重放**，另外两个都是**持久状态、必须纳入备份**。别再把它们混为一谈：

| 容器 | 状态真相 | 丢失代价 | 恢复手段 |
|---|---|---|---|
| **postgres·flowcart**（业务库） | 业务记录（商品/铺货/订单快照/投影表） | 业务数据全丢 | 必须 pg_dump |
| **postgres·temporal / temporal_visibility**（编排库） | **持久执行真相**：workflow history、在途执行、幂等锚点 | 在途 listing/order workflow 断链、幂等去重记忆丢失 | 必须 pg_dump |
| **rabbitmq** | Quorum 队列持久化，但**语义上可重放** | 未消费领域事件丢失，可由源头重建 | 不备份，重建（见 §3 前提） |

**为什么 RabbitMQ 不用备份**（不是因为它无状态，而是三条 invariant 让它可重放，ADR-0001）：
1. DB 为 record（先落业务库再发事件）；
2. 消费端幂等可重放（同一事件消费多次无副作用）；
3. money 事件永不作 first-write。

缺任一条，RabbitMQ 都从"可重放"降级为"需要备份"。**违背其中任何一条代码变更时，本文件需重新评估。**

**为什么 Temporal 必须备份（常见误区）**：Temporal 把 workflow execution 持久化到 temporal 库。对 ecom-flowcart，`listing-{id}` / order workflow 是**跨天、跨外部异步事件**的持久执行（ADR-0002/0003），其 Event History 既是审计轨迹也是幂等锚点。丢了 temporal 库 ≠ "从业务库重建"——业务库只记录结果，不记录"正在跑到哪一步"。

## 2. 备份对象与命令

备份对象 = postgres 容器内的三个 database：`flowcart`、`temporal`、`temporal_visibility`（同一 postgres 实例）。

- 格式：`pg_dump -Fc`（custom 格式，压缩、支持选择性 restore、`pg_restore` 友好）。
- 宿主端口：compose 将 postgres 映射到宿主 `5433`（避免与本地 5432 冲突）——脚本默认走容器网络内直连亦可，二者由 `PG*` 环境覆盖。
- 脚本：`docker/backup-postgres.sh`（双库顺序 dump；dry-run 模式校验脚本与连通性，无数据也可跑通）。

> ⚠️ 三库同一 postgres 实例，但**不存在跨库事务**：`pg_dump` 逐库执行，各快照之间存在秒级窗口。见 §4 一致性语义。

## 3. RabbitMQ / Temporal "重建"边界（成文说明）

**RabbitMQ** 重建 = `docker compose up`（空 `rabbitmq_data` 卷）重新初始化即可；队列/交换机定义若由应用启动时声明（declare），则会随消费者/生产者自动重建。若存在**非声明式**的管理面配置（DLX、policy、手动建的 exchange），需在重建清单中记录——本期由应用端 declare 承担，属可重建。触发重建：`docker compose down -v && docker compose up -d`（连卷重建，重新走 init + schema）。

**Temporal** 重建 ≠ 无成本：全新 temporal 库意味着所有历史 workflow 丢失。仅当可接受"丢弃全部在途执行、从业务当前状态重新铺货/重建订单 workflow"时才允许走全量重建。**任何需要保留在途编排的场景都必须走 pg_dump 恢复，而非重建。**

## 4. 一致性语义（双库非原子，接受 RPO 近似一致）

两库独立 `pg_dump`，无法取跨库一致点（v1 单机不做停编排/冻结）。因此：

- **语义**：快照间存在秒级不一致窗口。恢复后业务库可能是"较新"或"较旧"的真相，temporal 库则反映 dump 时刻的在途状态。
- **恢复次序与 reconcile**：以业务库为真相基座恢复，再恢复 temporal；随后对"业务库显示已建/已铺/已下单，但 temporal 无对应在途 workflow"的记录，走**幂等重放/人工 reconcile**（铺货链本身确定性幂等 listing-{id}，重触发即补齐，ADR-0003）。
- **不做**：备份窗口停编排取一致点（对单机 v1 收益 < 代价）；不假设 dump 点之间天然一致。

## 5. 何时点亮定时（当前为骨架期）

**当前代码库零 SQL migration、`app` 为骨架 web 空壳，`flowcart` 业务库无 schema/数据**；temporal schema 已由引导容器建立但尚无 workflow 数据。此阶段：

- ✅ **已就绪**：SOP + 脚本骨架 + dry-run 验证（跑 `backup-postgres.sh --dry-run` 确认连通与命令路径）。
- 🔜 **点亮定时 cron 的条件（任一满足即点亮）**：
  1. 首个 Flyway migration 落地、`flowcart` 出现真实业务 schema；**或**
  2. 开始有真实在途 workflow（铺货/订单）值得保留。
- 点亮方式（骨架期不接系统 cron，交由运维按环境接宿主 cron 或 compose 定时侧车）：
  - 宿主 cron：`0 2 * * * cd <repo> && docker/backup-postgres.sh >> docker/backups/backup.log 2>&1`（`BACKUP_DIR` 指到非易失路径）。
  - 恢复演练：**点亮定时前必须成功执行一次** `restore-postgres.sh` 到临时库，验证能 restore。

## 6. 恢复演练（验收门槛）

备份能否用，只有 restore 过才算数。点亮定时前至少演练一次：

1. `docker/backup-postgres.sh` 产出一份 dump；
2. 用 `docker/restore-postgres.sh`（或 `pg_restore`）恢复到临时 database，校验关键表行数与 schema 完整；
3. 记录演练结果；演练失败则备份不可信，不得点亮定时。

## 7. 备份目标位置与保留策略（骨架默认）

- 默认 `BACKUP_DIR=$(git rev-parse --show-toplevel)/docker/backups`（已被 .gitignore 拦截，不入库）；生产/共享环境用 `.env` 的 `BACKUP_DIR` 指到**与数据不同盘/网络挂载/S3 类**外部位置。
- 骨架期保留策略：默认本地保留最近 N=7 份（`BACKUP_RETENTION` 可覆盖），**同机备份防的是误删/逻辑错误，防不了盘坏**——文档明确：真正灾备需异机/对象存储，属点亮定时前的运维决策。
- ⚠️ 备份含明文数据（业务库 + 编排历史），落盘/传输须匹配环境安全要求，`.env` 凭据不得入库。

## 8. 口径纠偏记录（2026-09-09）

原 compose/README/architecture/ADR-0009 均写"RabbitMQ/Temporal 无状态可重建（历史在 temporal 库）"——把 **Temporal 误归入无状态**，与 ADR-0002"Temporal = 执行真相"自相矛盾。本次修正：Temporal 编排库纳入 pg_dump；仅 RabbitMQ 可重放（附三 invariant 前提）。同步改：`docker-compose.yml` 注释、`docs/architecture.md` §5、`docs/adr/0009-*.md`、本文件。
