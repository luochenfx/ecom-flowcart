# 架构总览（ecom-flowcart v1 设计包总入口）

> 本文件是 v1 设计包的**导航中枢**：模块边界、依赖方向、运行时/部署视图、契约资产索引。
> 各子决策的深度理由见 [ADR 索引](#adr-索引)；各领域细节见 [规范索引](#规范索引)；机器可读契约见 [schemas/](./schemas)。
> 领域术语（Listing / SPU / Order / Adapter / AI Step…）见 [CONTEXT.md](./CONTEXT.md)。

## 1. 系统定位

一件代发电商自动化工作流：**采集（1688 货源）→ 内容链（清洗/AI Step 生产 Listing 内容）→ 铺货（发布到淘宝/拼多多/速卖通）→ 订单回传（销售订单 → 1688 采购单 → 物流）→ 看板 HITL**。国内（1688 → 淘宝/拼多多）与跨境（1688 → 速卖通）双链路共用一套架构。

设计前提（已定基线）：单机部署，最低 4C8G、**推荐 8C16G**；开源社区协作模式（模块可 fork、契约稳定、core 不被平台污染）。

## 2. 架构骨架（总装结果）

```
┌─────────────────────────── 基础设施容器（docker-compose） ───────────────────────────┐
│                                                                                        │
│  ┌──────────────┐   ┌──────────────┐   ┌───────────────────────────────────────────┐  │
│  │   postgres   │   │   rabbitmq   │   │   temporal（Server：frontend/history/       │  │
│  │  flowcart 库 │   │ Quorum Queues│   │            matching/worker 自托管 + UI）     │  │
│  │  +temporal 库│   │（领域事件总线） │   │              （有状态编排引擎）             │  │
│  └──────┬───────┘   └──────┬───────┘   └──────────────────────┬────────────────────┘  │
│         │                  │                                   │                       │
└─────────┼──────────────────┼───────────────────────────────────┼───────────────────────┘
          │                  │                                   │
┌─────────▼──────────────────▼───────────────────────────────────▼───────────────────────┐
│  app —— 模块化单体（1 个 Spring Boot artifact，--role=api|worker|scheduler 可切）          │
│                                                                                          │
│  ┌──────────────────────────────── core（唯一共享层，零 Spring/平台依赖）────────────────┐ │
│  │  标准模型 POJO · JSON Schema · 契约接口（Capability / AI Step / envelope type）        │ │
│  └──────────────────────────────────────────┬───────────────────────────────────────────┘ │
│          ▲               ▲              ▲               ▲              ▲                  │
│  ┌───────┴──────┐ ┌──────┴──────┐ ┌─────┴──────┐ ┌──────┴───────┐ ┌──────┴───────┐        │
│  │   catalog    │ │   content   │ │  publish   │ │    order     │ │  projection  │        │
│  │ 采集/SPU/SKU │ │ AI Step 内容链│ │ 铺货(#11)  │ │ 同步/采购/RMA│ │   读侧/看板   │        │
│  │ /MediaAsset  │ │ (#12 content-│ │ listing-   │ │ (#8 order-   │ │  (投影表)     │        │
│  │  (#7)        │ │  {id} wf)    │ │ {id} wf    │ │  {platform}- │ │              │        │
│  └──────────────┘ └──────────────┘ └────────────┘ │  {orderNo})  │ └──────┬───────┘        │
│                                                   └──────┬───────┘        │                │
│                    ┌──────────────┐    ┌──────────────────▼────────────────▼───┐          │
│                    │ adapter-host │    │                api                     │          │
│                    │ Java SPI 装配 │    │ REST /api/v1/*（看板/铺货触发/人工      │          │
│                    │ #9 Adapter   │    │ reconcile）；MCP transport seam 预留    │          │
│                    │ + #12 Step   │    └─────────────────────────────────────────┘          │
│                    └──────────────┘                                                          │
└──────────────────────────────────────────────────────────────────────────────────────────────┘

外部端点：AI 模型后端（OpenAI / 本地 Ollama·vLLM / 国产通义·DeepSeek，OpenAI-compatible HTTP）
```

## 3. 模块边界（每个模块的职责）

| 模块 | 职责 | 依据 |
|---|---|---|
| `core` | 标准模型 POJO + JSON Schema + 契约接口（Capability / AI Step / envelope type）+ 领域语义；**零 Spring/平台依赖，一切模块只依赖它** | #7/#9/#10/#12 |
| `catalog` | 商品 master：采集（OfferFetch）→ SPU/SKU/MediaAsset；canonical i18n 回填 | #7（ADR-0004） |
| `content` | 内容链：每 Listing 一 `content-{listingId}` workflow，AI Step 产改写稿/价格/媒体；产物 = Listing 内容就绪 | #12（ADR-0008） |
| `publish` | 铺货：每 Listing 一 `listing-{id}` workflow（确定性 WorkflowId 幂等），只读就绪 Listing | #11（ADR-0003） |
| `order` | 订单：同步（拉取为真相）/建单快照/采购单 1:N/RMA；每单独立 workflow | #8（ADR-0005） |
| `projection` | 读侧：execution_projection 等投影表承载看板/列表查询，不含状态机逻辑 | #13（ADR-0002） |
| `api` | 对外 REST `/api/v1/*`（查询/铺货触发/人工 reconcile）；MCP transport seam 预留（首版不做） | #14（ADR-0009） |
| `adapter-host` | SPI 装配：加载各平台 Adapter（#9）+ AI Step（#12），Composition Root 之一 | #9（ADR-0007） |
| `worker-runtime` | Temporal/RabbitMQ worker 启动与角色管理 | #13（ADR-0002） |

**模块间通信铁律**：业务模块间不直接调实现——跨模块协作经 workflow start/signal（Temporal）或领域事件（RabbitMQ，仅广播已落库事实）；读侧只读投影表。总线只流 Domain Event 单层（无 raw/cleaned lane）。

## 4. 依赖方向与禁环

**单一规则：`core` ← 一切模块。** 模块之间只依赖接口（SPI），不依赖实现；`adapter-host` 与 `worker-runtime` 是仅有的两个 Composition Root（知道所有模块的地方），位于最外层。

```
编译期禁环（ArchUnit 自动拦，社区 PR 过不了 CI）：
① adapter / AI Step 实现 → 业务模块        （插件不反向依赖宿主）
② 业务模块 → 具体平台 / 模型类             （只认 core 接口——平台污染 master 的代码级镜像）
③ 读侧 projection → 写侧内部实现           （只读 core 模型 + 投影表）
```

## 5. 运行时与部署视图

**角色即 `--role`**：同一 artifact 启动时可切 `api`（REST）/ `worker`（Temporal worker + Rabbit consumer + scheduler）/ `scheduler`（周期调度：订单轮询拉取、channel sync）。单机起步合一（`--role=api,worker,scheduler`）。

| compose 服务 | 角色 | 资源预算（16G 内） |
|---|---|---|
| `postgres` | 单实例两 database：`flowcart`（业务）+ `temporal`（编排历史） | 3–4G |
| `rabbitmq` | Quorum Queues + 管理插件 | 0.5–1G |
| `temporal` | 自托管 Server 4 角色（连 temporal 库；auto-setup 镜像 deprecated，2026-09-09 起改 server + admin-tools 引导） | 2–3G |
| `app` | 模块化单体（合一 role） | 4–6G |
| `temporal-ui`（可选） | 编排运维看板 | 0.5G |

- **备份（口径纠偏，2026-09-09）**：postgres 双库 pg_dump 定时——业务库 `flowcart` **与** Temporal 编排库（`temporal` / `temporal_visibility`）**都须纳入备份**。状态归类：**Temporal 非无状态可重建**，其编排库是持久执行真相（workflow 历史/幂等锚点，见 ADR-0002）；仅 **RabbitMQ 可重放**（依赖"DB 为 record + 消费端幂等 + money 非 first-write"，ADR-0001）。备份/恢复语义（双库非原子、RPO 近似一致、restore 后 reconcile、点亮定时条件）见 [ops 备份/恢复 SOP](./ops/backup-restore.md)。
- **Temporal 引导（一次性容器，运行期峰值约 0.5G 后退出）**：postgres 首次初始化建 `temporal`/`temporal_visibility` 库（`docker/init/`）→ `temporal-setup`（admin-tools，sql-tool 建 schema）→ Server → `temporal-init`（admin-tools，注册 default namespace）→ app；升级 = 同步更换 `temporalio/server` 与 `temporalio/admin-tools` 两个 tag。
- **拆分演进信号**：① 单 worker CPU 饱和 / API 延迟被 AI Step 长调用拖累 → 拆 `app-worker` 独立容器（同 artifact `--role=worker`）；② 多机 → 才引入服务发现/Gateway（届时再议）。

## 6. 契约资产索引

契约三形态**同仓同 commit**（变更原子），无独立 registry、不引 codegen：

| 形态 | 位置 | 内容 |
|---|---|---|
| JSON Schema | [`schemas/`](./schemas) | `product-catalog.schema.json`（#7）/ `order.schema.json`（#8）/ `message.schema.json`（#10） |
| Java 契约（实现期） | `core-contracts` Maven 模块 | Capability 接口族 + AdapterException（#9）/ AI Step + LLMProvider + ModelResolver seam（#12）/ envelope 类型（#10） |
| 规范文档 | [`docs/specs/`](./docs/specs) | 0001–0006 |

### ADR 索引

| ADR | 决策 |
|---|---|
| [0001](./docs/adr/0001-rabbitmq-quorum-as-v1-message-bus.md) | 消息总线 = RabbitMQ（Quorum），DLQ 隔离舱 + 信号源、绝不自愈 |
| [0002](./docs/adr/0002-temporal-for-workflow-orchestration.md) | 工作流引擎 = Temporal 自托管（8C16G 推荐档）；有状态链进 workflow、无状态进总线 |
| [0003](./docs/adr/0003-listing-idempotency-via-deterministic-workflow-id.md) | 铺货幂等 = 确定性 WorkflowId，不建幂等登记表 |
| [0004](./docs/adr/0004-product-catalog-model.md) | 商品模型：SPU/SKU master + Listing 平台特化 + MediaAsset（LOCALIZED 预留） |
| [0005](./docs/adr/0005-order-snapshot-model.md) | 订单模型：建单快照一等实体 + Order/PurchaseOrder/RMA |
| [0006](./docs/adr/0006-message-schema-and-versioning.md) | 消息 schema：窄总线单层 Domain Event + envelope 契约 + repo 即 registry |
| [0007](./docs/adr/0007-adapter-plugin-contract.md) | 平台 Adapter：Capability 接口族 + core 零平台依赖 + Java SPI |
| [0008](./docs/adr/0008-ai-step-architecture.md) | AI Step：内容链分离 + 字段级契约 + LLMProvider SPI（编排插槽预留） |
| [0009](./docs/adr/0009-modular-monolith-and-deployment-view.md) | 架构骨架：模块化单体 + core 依赖规则 + 单仓契约 + REST 主入口 + compose 部署 |

### 规范索引

| Spec | 内容 |
|---|---|
| [0001](./docs/specs/0001-listing-publish-idempotency.md) | 铺货幂等状态机 + Adapter 错误分类 + 消息/API 分层 |
| [0002](./docs/specs/0002-product-catalog-model.md) | 商品标准模型全量规范（SPU/SKU/Listing/MediaAsset/多语言/血缘） |
| [0003](./docs/specs/0003-order-model.md) | 订单标准模型全量规范（快照/双轴状态/同步/地址/RMA） |
| [0004](./docs/specs/0004-message-schema-versioning.md) | envelope 契约 + 版本规则 + 追踪 id + DLQ 两层 |
| [0005](./docs/specs/0005-adapter-plugin-contract.md) | Adapter 契约（含能力接口签名草图） |
| [0006](./docs/specs/0006-ai-step-model.md) | AI Step 契约（含签名草图 + 首批 Step 清单） |

## 7. 遗留 fog（Not yet specified，不拍脑袋填）

- 采集层触发形态：手动粘贴商品链接 / 批量 API 拉取 / 爬虫——上游数据源未定；
- 多租户隔离：开源首版是否需要——取决于商业化意图；
- 数据血缘审计消费形态：对象级血缘已定（#7），审计如何被看板/查询消费未定；
- 清洗/翻译链路的语言对与具体清洗规则——锚定数据源平台后细化。
