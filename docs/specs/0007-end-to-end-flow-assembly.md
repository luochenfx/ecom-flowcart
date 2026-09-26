# 规范：端到端链路打通（编排链 + 装配根 + catalog 落 Postgres）

> 来源：Grilling session（2026-09-21），议题「跑通从采集到铺货的全流程」
> 依赖：[ADR-0002（Temporal）](../adr/0002-temporal-for-workflow-orchestration.md)、[ADR-0009（模块化单体 + 装配根）](../adr/0009-modular-monolith-and-deployment-view.md)、[规范 0001（铺货幂等）](./0001-listing-publish-idempotency.md)、[规范 0002（商品模型）](./0002-product-catalog-model.md)、[规范 0006（AI Step / 内容链）](./0006-ai-step-model.md)
> 状态：v1 设计期决议（**实现期总纲**，衔接 #24 发布准备）
> 范围声明：本规范**只解决"链路能跑通"**。以下问题明确**不在本规范范围**（见 §9）：消费并发受控、幂等与去重的进阶形态、失败分类与退避策略、背压与优先级。

## 1. 决策概览

- **编排链（listing flow）是本次新增的唯一架构层**：父级 workflow `fulfillment-{spuId}-{channelId}`，以 **child workflow** 顺序串联「Listing 装配 → 内容链 → 铺货链」。业务逻辑不在此层，只做调度与传参。
- **采集不升格为 workflow**：`CatalogIngestService.ingest` 保持纯 Java 服务，由 REST 入口**同步**调用；采集完成后**另起**编排链。
- **REST 入口落 `api` 模块**，`POST /api/v1/ingest` 同步采集 + 启动编排链，返回 `202` + 链路坐标；`GET /api/v1/flows/{workflowId}` 提供链路查询。
- **装配根补齐**：`adapter-host` 提供 `AdapterHost`（ServiceLoader 汇总 Adapter / AI Step）；`app` 用 Spring `@Configuration` + `@ConditionalOnProperty` 按 `app.role` 启停 worker；`PublishWorkerFactory` / `OrderWorkerFactory` 补 `start(...)`。
- **catalog 落 Postgres**：新增 `PostgresCatalogStore`（`JdbcTemplate` + 单表 JSONB），Flyway 脚本落 `app/src/main/resources/db/migration/V1__catalog.sql`。`OrderStore` / `PublishStateStore` **本次不动**（保持 JSON 文件）。
- **测试 Adapter 走真 SPI**：新建 `adapter-fake` 模块实现 `PublishCapability` 等销售侧能力，**仅 test scope** 进 `app`——生产 artifact 绝不携带。
- **内容就绪是硬前置**：编排链在启动铺货链前断言 `degraded_steps` 为空；非空则整链失败（不铺残次品）。
- **零契约变更**：本规范不触碰 `core-contracts` 的字段 / 方法签名，不触 `AGENTS.md` 的契约闸门。所有新类型落在 `worker-runtime` / `api` / `adapter-host` / `app`。

## 2. 「全流程」的定义与断链现状

### 2.1 目标链路

```
REST 请求(sourceRef, channelId, targetCategory, locales, chain)
   │
   ├─[同步]─► CatalogIngestService.ingest(sourceRef)
   │            └─ OfferFetchCapability（SPI，adapter-1688）→ OfferData → OfferCatalogMapper → CatalogStore.put
   │          返回 IngestResult(spuId, skuIds, mediaIds)
   │
   └─[异步]─► 编排链 fulfillment-{spuId}-{channelId}
                │
                ├─ activity: 装配 Listing（ListingDraftFactory → CatalogStore.put）
                │
                ├─ child workflow: content-{listingId}      （content-task-queue）
                │     └─ 每 Step 一 activity，边跑边落库
                │
                ├─ activity: 断言内容就绪（degraded_steps 为空）
                │
                └─ child workflow: listing-{spuId}-{channelId}（publish-task-queue）
                      └─ inspect → reconcile-first + add → 收敛 / AMBIGUOUS 挂起
```

### 2.2 断链现状（实现前的证据）

| 断点 | 证据 |
|---|---|
| **catalog → content 之间无衔接** | `CatalogIngestService.ingest` 返回 `IngestResult(spuId, skuIds, mediaIds)`，**不含 Listing**；而 `ContentWorkflowInput(spuId, listingId, plan)` 要 `listingId` |
| **Listing 无人装配** | `ListingDraftFactory`（`content/listing/`）全仓**只在定义处出现**，无任何调用点 |
| **content → publish 之间无衔接** | `PublishWorkflowInput(Listing)` 要求就绪 Listing；`ContentWorkflowImpl` 返回 `ContentWorkflowResult`，不含 Listing、不启动后续 |
| **采集非 workflow** | `CatalogIngestService` 是纯 Java 服务（正确，见 §3），但因此链条没有承载者 |
| **装配根缺失** | `adapter-host` / `api` / `projection` 三模块为空壳（仅 `.gitkeep`）；`PublishWorkerFactory` / `OrderWorkerFactory` 只有 `register(...)` 无 `start(...)`；`application.yml` 中 datasource / flyway / rabbitmq / temporal **全部为注释占位** |
| **存储未落真库** | `app/src/main/resources/db/migration/` 只有 `README.md`，零 SQL；三个 store 端口均以 JSON 文件实现 |

## 3. 采集为何不升格为 workflow

**决策**：采集保持同步调用，不进 Temporal。

理由：

1. **采集是单次外部调用 + 一次落库**——不是多步长事务，套 workflow 只增加一层 Event History 与 task queue 注册，收益为负。
2. **REST 入口需要同步返回 `spuId`**——编排链的 workflowId（`fulfillment-{spuId}-{channelId}`，见 §4.1）依赖它。若采集异步化，REST 只能返回一个 workflowId，调用方需再轮询取 spuId，v1 复杂度不划算。
3. **Temporal 的价值在长耗时 / 多步 / 需重放处**——内容链（多 Step、LLM 花钱、硬依赖失败要重放）与铺货链（外部 API 视为必失败、需 AMBIGUOUS 挂起 + signal）已充分体现；采集不属此类。
4. **边界清晰**：`AdapterException` 直接以 HTTP 状态码呈现（§6.3），调用方立即知道"这个 offer 取不到"。

**代价（如实登记）**：REST 线程在采集期间被占用（单次 HTTP 调用量级，可接受）；采集与编排链启之间存在一个极短的窗口——采集已落库、编排链未启动，此时进程崩溃则该次请求的后续链路不会发生（调用方需重新提交，重新提交会命中幂等：`store.put` 按 spuId 覆盖，编排链 workflowId 确定性）。

## 4. 编排链（listing flow）

### 4.1 坐标与幂等

| 对象 | workflowId | task queue |
|---|---|---|
| 编排链 | `fulfillment-{spuId}-{channelId}` | `flow-task-queue` |
| 内容链 | `content-{listingId}` | `content-task-queue` |
| 铺货链 | `listing-{spuId}-{channelId}` | `publish-task-queue` |

三处 id 推导**必须口径一致**（同一 Listing 的三个视角）。`PublishWorkflowInput` 构造器已有强校验（`listingId` 必须等于 `listing-{spuId}-{channelId}` 的推导值），编排链沿用同口径。

新增 `ListingFlowRuntime`（`worker-runtime`），与 `ContentRuntime` / `PublishRuntime` 同形态：常量 `TASK_QUEUE` / `WORKFLOW_ID_PREFIX` + 静态 `workflowIdFor(spuId, channelId)`。

**幂等策略沿用既有组合**（与 `ContentWorkflowLauncher` / `PublishWorkflowLauncher` 一致）：

- `WorkflowIdReusePolicy = ALLOW_DUPLICATE_FAILED_ONLY`：前一次 failed → 放行新 run；completed → 拒绝
- `WorkflowIdConflictPolicy = FAIL`：运行中 / 挂起中的重复触发被拒

不建幂等登记表（ADR-0003）。

### 4.2 输入与输出

```java
// worker-runtime（非 core，不触契约闸门）
public record ListingFlowWorkflowInput(
        String spuId,
        String channelId,
        CategoryRef targetCategory,   // 目标平台叶子类目
        List<String> locales,          // 提交用语言集
        ContentChainKind chain,        // DOMESTIC | CROSS_BORDER
        String plan                    // ContentPlan 的 Step 清单描述（见 §4.3）
) {}

public record ListingFlowWorkflowResult(
        String spuId, String listingId,
        boolean published, String platformItemId, String platformItemUrl,
        boolean contentReady, List<DegradedStep> degradedSteps,
        String reason                  // 失败/挂起原因（正常收敛时为空）
) {}
```

### 4.3 `ContentPlan` 的来源：请求方声明链路类型

**决策**：请求体声明 `chain: domestic | cross_border`，编排链内映射为 `ContentPlan`。

映射（落在 `worker-runtime`，作为配置而非硬编码）：

| chain | ContentPlan |
|---|---|
| `domestic` | `ContentPlan.standard().without(I18N_BACKFILL)` |
| `cross_border` | `ContentPlan.standard()` |

理由（**与「模块间不可强耦合」直接相关**）：若改为"由 `channelId` 推导链路类型"，编排层就必须知道 channel 的业务语义（要查渠道表、要知道淘宝是国内平台）——这是编排层对业务域的耦合。让请求方声明它本来就知道的事实，是零耦合的解法。

### 4.4 编排链的控制流

```
1. 装配 Listing
   activity: store.get(spuId) → ListingDraftFactory.draft(master, channelId, targetCategory, locales)
             → store.put(新文档)；返回 listingId 与装配后的 Listing

2. 内容链（child workflow，同步等待）
   Workflow.newChildWorkflowStub(ContentWorkflow.class, childOptions(ContentRuntime.TASK_QUEUE))
     .run(new ContentWorkflowInput(spuId, listingId, plan))
   → 硬依赖失败时 child 抛异常，冒泡至编排链 → 整链 failed

3. 内容就绪断言（activity）
   store.get(spuId) → 定位 listing → listing.degradedSteps() 非空 ⇒ 抛 ApplicationFailure(newNonRetryableFailure)
   （见 §5）

4. 铺货链（child workflow，同步等待，无超时）
   Workflow.newChildWorkflowStub(PublishWorkflow.class, childOptions(PublishRuntime.TASK_QUEUE))
     .run(new PublishWorkflowInput(就绪 Listing))
   → 停在 AMBIGUOUS 时 child 挂起，编排链同步挂起（见 §4.5）
```

**关键技术约束**：必须用 `Workflow.newChildWorkflowStub(...)`——**不得**在 activity 内调 `ContentWorkflowLauncher.run(...)`。后者是 Temporal 官方明列的反模式：activity 阻塞、重试时重复启动子链、子链不随父链取消传播。

**跨 task queue 的 child workflow**：Temporal 支持，要求两个队列的 worker 均在线。`app` 合一 role 部署时天然满足；拆 `--role=worker` 时需保证三类 worker 都在。

### 4.5 长期挂起是合法状态

铺货链停在 `AMBIGUOUS`（外部调用结果未知，等人工/对账裁定）时，编排链**同步挂起**，可能持续数天。

**决策**：不设超时，允许长期挂起。超时机制后续单独考虑。

理由：铺货的 `AMBIGUOUS` 语义是"我们不知道平台是否已创建商品"，此时任何自动超时都可能触发重复铺货（违反 ADR-0003「AMBIGUOUS 绝不自动重发」）。父子链形成一棵树，Temporal UI 上一眼可见"哪条链卡在等人"。

**后果**：`GET /api/v1/flows/{workflowId}` 长时间返回 running。**running 不等于出错**——这是本设计的显式契约，需在 API 文档与看板文案中体现。

**signal 走捷径**：裁定 signal（`confirmPublished` / `confirmNotEffective` / `reject`）通过 `PublishWorkflowLauncher.stub(listingId)` **直发铺货链**，不经过编排链。编排链只是跟随者，不是信号路由器。

## 5. 内容就绪是铺货的硬前置

**决策**：编排链在启动铺货链前断言 `degraded_steps` 为空；非空则整链失败，不启动铺货。

背景：`ContentPlan.standard()` 把 `i18n.backfill`（跨境无内容不可铺）与 `media.process`（无图不能铺）标为 `critical`，设计意图是"这两步不到位不能铺"——但当前**没有任何代码执行这个意图**（`PublishWorkflowInput` 只校验 listingId 口径，不校验 `degraded_steps`）。

同时，`ContentWorkflowLauncher` 的 javadoc 已登记缺口：**degraded 但 completed 的 Listing 无法用同一 workflowId 重新生成内容**（命中 "completed → 拒绝"）。本规范**不解决**该缺口（属"人工重新生成内容"的触发形态，见 §9），而是通过"降级即不许铺货"把它转化为一个**明确的失败信号**，交还调用方决策。

**取舍（如实登记）**：本决策使"LLM 欠费 → 降级 → 整链失败"成为硬失败。选择此侧的核心理由：铺出去的残次品 Listing 要人工下架，代价高于一次失败重试。

**fail 之后的重跑路径**：编排链 failed → `ALLOW_DUPLICATE_FAILED_ONLY` 放行同 id 新 run。但注意子链自身的 reuse policy 同样只放行 failed 的——若内容链是 **completed 但 degraded**，它拒绝重跑，新 run 会在内容链这一步直接命中 `AlreadyStarted` 拿到旧结果。**这是本规范登记的已知缺口**，需与 §9 的"人工重新生成内容"一并解决。

## 6. REST 入口（`api` 模块）

### 6.1 端点

| 方法 | 路径 | 语义 |
|---|---|---|
| `POST` | `/api/v1/ingest` | 同步采集 + 启动编排链；返回 `202` + 链路坐标 |
| `GET` | `/api/v1/flows/{workflowId}` | 查询编排链状态与结果 |

`GET` 是**必须的**——没有它，"跑通全流程"只能靠去 Temporal UI 肉眼看，链路不可自动验证。

### 6.2 `POST /api/v1/ingest`

请求体（snake_case，与既有 schema 命名一致）：

```json
{
  "source_ref": { "platform": "1688", "external_id": "...", "url": "...", "fetched_at": "..." },
  "channel_id": "taobao-a",
  "target_category": { "taxonomy": "taobao", "value": "...", "label": "..." },
  "locales": ["zh-CN"],
  "chain": "domestic"
}
```

`target_category` / `locales` / `chain` 由请求方携带（理由见 §4.3 与 CONTEXT「Channel」）。

响应 `202 Accepted`：

```json
{
  "spu_id": "spu-1688-...",
  "listing_id": "listing-spu-1688-...-taobao-a",
  "flow_workflow_id": "fulfillment-spu-1688-...-taobao-a",
  "sku_ids": ["..."],
  "media_ids": ["..."]
}
```

`sku_ids` / `media_ids` 取自同步的 `IngestResult`（采集已完成，立即可知）；`flow_workflow_id` 是后续追踪坐标。

### 6.3 失败映射

| 情形 | HTTP | 是否启动编排链 |
|---|---|---|
| 采集抛 `AdapterException(RETRYABLE)` | `503` | **否**（未落库，起链无意义） |
| 采集抛 `AdapterException(NON_RETRYABLE)` | `422` | **否** |
| 请求体校验失败（缺必填 / 枚举非法） | `400` | 否 |
| 编排链启动成功（无论后续结果） | `202` | 是 |

**采集失败绝不启动编排链**——避免产生一条注定在第一步装配就失败的 zombie 链。

### 6.4 依赖方向核查

`api` 新增依赖：`catalog`（取 `CatalogIngestService` / `IngestResult`）、`worker-runtime`（取编排链 launcher）、`core-contracts`。

**禁环核查**：
- 禁环①（adapter/AI Step 实现 → 业务模块）：不适用
- 禁环②（业务模块 → 具体平台 / 模型类）：`api` 不 import 任何平台类
- 禁环③（读侧 projection → 写侧内部实现）：`api` 是**入口层**不是读侧投影（`projection` 才是读侧），不违反

实现时必须新增/复核 ArchUnit 规则，把 `api → worker-runtime` / `api → catalog` 显式列入允许边。

## 7. 装配根

### 7.1 `AdapterHost`（`adapter-host` 模块）

新增 `io.autocommerce.adapterhost.AdapterHost`：

```java
public final class AdapterHost {
    // ServiceLoader.load(PlatformAdapterProvider.class) 汇总所有 Adapter
    public static AdapterHost load();
    public Set<String> platforms();
    public <T extends Capability> T capability(String platform, Class<T> type);
    // 同理汇总 AiStepProvider → List<AiStep>（ContentAiStepProvider javadoc 已把 adapter-host 定为装配点）
    public List<AiStep> aiSteps();
}
```

**依赖**：仅 `core-contracts`（`PlatformAdapterProvider` / `Capability` / `AiStepProvider` 都在 core）。
**绝不能依赖 `adapter-1688`**——否则编译期就把 1688 焊死，破坏插件化。具体 Adapter 的 jar 由 `app` 的 **runtime classpath** 提供（`app/pom.xml` 已依赖全部内部模块）。

### 7.2 `app` 的 Spring 装配

`application.yml` 启用基础设施配置（当前全为注释占位）：

```yaml
spring:
  datasource:        # Postgres（flowcart 库）
  flyway:            # 启用，脚本源 = classpath:db/migration
app:
  role: api,worker,scheduler
  media-root: /data/media
  temporal:
    target: temporal:7233     # 连 compose 的真 server
    namespace: default
  flow:
    channels:                  # 可选：channelId → chain 映射（请求方未声明时的兜底）
```

Spring 配置类在 `app` 内定义 bean：`WorkflowClient`、`CatalogStore`（`PostgresCatalogStore`）、`AdapterHost`、编排链与三条子链的 launcher。

**角色切换**：一个 `@Component` 监听 `ApplicationReadyEvent`，解析 `app.role`（逗号串）并启动对应 worker。各 `WorkerFactory` 的 bean 用 `@ConditionalOnProperty` 控制。

**需补的代码**：`PublishWorkerFactory.start(...)` 与 `OrderWorkerFactory.start(...)`（当前只有 `register(...)`）；`ContentWorkerFactory.start(...)` 已存在但签名要适配 `AdapterHost`（其 `CatalogStore` 参数保持不变）。

### 7.3 Temporal 真 server

生产装配连 compose 的 `temporal:7233`（ADR-0002 自托管）。

**单测仍用 `TestWorkflowEnvironment`（in-process）**——"Temporal 连真 server"指的是**生产装配路径**，不是所有测试。e2e 验收连真 server（§8）。

## 8. catalog 落 Postgres

### 8.1 端口不变

`CatalogStore` 接口签名**不动**（`put` / `get(spuId)` / `listSpuIds`）——这正是当初留口的意图（其 javadoc 明写"真库表结构留待装配点单独收敛，届时以同一端口换实现"）。

### 8.2 表结构

```sql
-- app/src/main/resources/db/migration/V1__catalog.sql
CREATE TABLE catalog_product (
    spu_id     text        PRIMARY KEY,
    doc        jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 不合规文档写不进去（schema_version 是 product-catalog.schema.json 的 required + const）
-- 注意：不能写成 `doc ->> 'schema_version' = '0.1.0'` —— 三值逻辑下会放过缺键 / 值为 null 的文档（见下决策明细）
ALTER TABLE catalog_product
    ADD CONSTRAINT catalog_product_schema_version
    CHECK (doc ? 'schema_version' AND doc -> 'schema_version' = '"0.1.0"'::jsonb);
```

**决策明细**：
- **CHECK 用两项合取，不做 `->>` 文本抽取**：`doc ->> 'schema_version' = '0.1.0'` 在三值逻辑下会**放过不合规文档**——键缺失时 `->>` 返回 NULL，`NULL = '0.1.0'` 求值为 NULL，而 CHECK 视 NULL 为通过 ⇒ 缺 `schema_version` 的文档被接受（它是 `product-catalog.schema.json` 的 `required` + `const`）。真 Postgres 16 实测：`->>` 版本接受 `{"nope":1}` 与 `{"schema_version":null}` 两条；两项合取版本只接受 `{"schema_version":"0.1.0"}`。`doc ? 'k'` 保证键存在，`doc -> 'k' = '"0.1.0"'::jsonb` 直接比 jsonb 值（不做文本抽取），键缺失与值为 null / 数字 / 数组都落到「不相等」而非 NULL。**本仓 JSONB 判等一律禁 `->>`**（本节首版误写作 `->>`，2026-09-26 就地订正为与已发布 `V1__catalog.sql` 一致的形式；`V1__catalog.sql` 与 `app/src/main/resources/db/migration/README.md` 注释里「spec §8.2 的写法」均指该首版——已应用脚本按纪律不原地编辑）。
- **不加 GIN 索引**：v1 无按 doc 内容查询的需求；`listSpuIds()` 只需扫主键。
- **不加来源平台列**：平台信息在 `doc` 内，加列即第二真相源。
- **加 `created_at`**：成本为零，未来排查需要。
- `updated_at` 由 `PostgresCatalogStore` 显式写入（不用触发器——保持"业务模块不知道存储细节"的对称性）。

### 8.3 实现方式

`PostgresCatalogStore implements CatalogStore`，用 **`JdbcTemplate`**（非 JPA）：

理由：`CatalogStore` 操作的是 `doc` 整列，**没有关系建模**（一个 text 主键 + 一个 jsonb 列），JPA 的实体映射与 `AttributeConverter` 在此收益为零、配置成本非零。序列化沿用 `JsonFileCatalogStore` 的既有 Jackson 配置（snake_case、non-null、INDENT_OUTPUT 可去）。

**单条 SQL，不需显式事务**。

`put` 沿用 `requireSingleSpu` 校验（spus() 恰 1 条）——与 JSON 实现保持一致的前置校验。

### 8.4 兼容性

`JsonFileCatalogStore` **保留**（测试仍用它，避免单测依赖数据库）。装配点（`app`）选择 Postgres 实现。

**Flyway 只在 `app` 跑**（唯一部署单元）；`catalog` 模块保持零 SQL——与"业务模块不知道存储细节"一致。

### 8.5 本次不动的存储

`OrderStore` / `PublishStateStore` **保持 JSON 文件实现**。理由：本次目标是链路打通而非存储层全量迁移，且换 store 实现不影响链路形状。

**登记的风险**：铺货链与内容链在编排下都会写文件，`JsonFilePublishStateStore` 的并发语义比测试环境更暴露。若 e2e 出现偶发失败，需回来把 `PublishStateStore` 也落 Postgres。

## 9. 测试 Adapter 与验收

### 9.1 `adapter-fake` 模块

**现状**：全仓**没有任何销售平台 Adapter**——`adapter-1688` 的能力清单是 `{OfferFetch, Purchase, Auth}`，不含 `PublishCapability`。因此编排链走到铺货环节时 `getCapability(PublishCapability.class)` 必然抛异常。

**决策**：新建 `adapter-fake` 模块（**main scope**，非 test scope），实现 `PlatformAdapterProvider`，platform = `"fake-sales"`，能力 = `{PublishCapability}`。

- 走**完全相同的 SPI 路径**（`META-INF/services/io.autocommerce.core.contract.PlatformAdapterProvider`），因此同时验证了"装配根能否把销售平台 Adapter 装进来"。
- 行为可控：可配置返回 `PUBLISHED` / `AMBIGUOUS` / `REJECTED` / `RETRYABLE`——**必须能返回 `AMBIGUOUS`**，否则挂起-裁定路径未被覆盖。
- **不是 mock**：它走真实的 `adapter-host` 装配、真实的 `PublishService` 状态机，只替代"外部平台"这一个边界（与本仓既有测试哲学一致，见 `ContentWorkerFactory` javadoc）。

**模块依赖**：`adapter-fake` → `core-contracts`（与 `adapter-1688` 对称）。
**引入方式**：`app/pom.xml` 以 **test scope** 引入 → 生产 artifact 绝不携带。
**e2e 装配**：e2e profile 下 `app` 的 Spring 装配把 platform 指向 `fake-sales`。

### 9.2 验收命令

| 命令 | 内容 | 前置 |
|---|---|---|
| `mvn clean test` | 全 reactor 单测 + in-process Temporal 测试 | 无（不需要 docker） |
| `mvn clean verify -Pe2e` | 端到端：真 Temporal server + 真 Postgres + 真 RabbitMQ | `docker compose up -d` |

**CI 口径**：默认只跑 `mvn clean test`。**e2e 不进默认 CI**（起 5 个容器的成本与不稳定性不划算），作为手动 / 夜间验收。

### 9.3 e2e 验收断言

一条完整链路，至少断言：

1. `POST /api/v1/ingest` 返回 `202` + 正确坐标（spuId 与确定性推导一致）
2. `CatalogStore` 中出现该 SPU 文档，且 `listings()` 含装配出的 Listing
3. 内容链完成，`degraded_steps` 为空（fake LLM 端点返回正常结果）
4. 铺货链收敛 `PUBLISHED`，`PublishStateStore` 落 PUBLISHED 事实
5. `GET /api/v1/flows/{workflowId}` 返回 completed + platformItemId
6. **第二条路径**：让 fake Adapter 返回 `AMBIGUOUS` → 编排链挂起 → 发 `confirmPublished` signal → 编排链收敛（验证挂起-裁定路径）
7. **第三条路径**：让某硬依赖 Step 失败 → 编排链 failed（验证 §5 的断言）
8. **第四条路径**：采集抛 `RETRYABLE` → HTTP `503`，且**无编排链被启动**（查 Temporal 无该 workflowId）

## 10. 文件级落地清单（MVP 顺序，每步可独立验证）

| 步 | 内容 | 模块 | 验证 |
|---|---|---|---|
| 1 | `PostgresCatalogStore` + `V1__catalog.sql` | `catalog` / `app` | 单测（Testcontainers 或手动 compose）+ schema 校验 |
| 2 | `AdapterHost` | `adapter-host` | 单测：ServiceLoader 能发现 adapter-1688 的 provider |
| 3 | `adapter-fake` 模块 | `adapter-fake` | 单测：SPI 发现 + 四种 disposition 可控 |
| 4 | `ListingFlowRuntime` + `ListingFlowWorkflowInput/Result` + `ListingFlowWorkflow(Impl)` + `ListingFlowWorkflowLauncher` + `ListingFlowActivities(Impl)`（装配 Listing / 内容就绪断言） | `worker-runtime` | in-process Temporal 单测（child workflow 全程） |
| 5 | `PublishWorkerFactory.start(...)` / `OrderWorkerFactory.start(...)` | `worker-runtime` | 单测 |
| 6 | `api` 模块：两个端点 + DTO + 异常映射 | `api` | MockMvc / WebTestClient 单测 |
| 7 | `app` Spring 装配：datasource / temporal / AdapterHost / 各 launcher / role 启停 | `app` | `@SpringBootTest` 上下文加载 |
| 8 | e2e profile + 4 条断言路径 | `app`（test） | `mvn clean verify -Pe2e` |
| 9 | 文档同步（§11） | `docs` | 人工核对 |

## 11. 需同步的文档

| 文档 | 变更 |
|---|---|
| `docs/architecture.md` | 模块边界表补 `api` / `adapter-host` 的实质职责；新增编排链说明；§7 fog 移除"采集层触发形态"（本次已定：REST 入口）+ 新增"人工重新生成内容"（见 §9 缺口） |
| `docs/architecture.md` §规范索引 | 加 0007 |
| `CONTEXT.md` | 已加：采集（ingest）、编排链（listing flow）、内容就绪（content ready）、测试 Adapter（test adapter）、链路坐标（flow identity）、链路查询（flow query） |
| `README.md` | 状态推进：从"设计期收官"到"端到端链路打通"；快速开始补 e2e 命令 |
| `app/src/main/resources/db/migration/README.md` | 说明 V1 的实际内容 |

## 12. 明确不做的事（范围外）

以下问题**本规范不解决**，实现时若遇到**不要顺手做**：

| 项 | 归属 |
|---|---|
| 消费并发受控 | 后续 spec（用户明确划出） |
| 幂等与去重的进阶形态（如 activity 级幂等键） | 后续 spec；ADR-0003 已定 v1 以 execution 级唯一性为界 |
| 失败分类与退避策略调参 | 后续 spec |
| 背压与优先级 | 后续 spec |
| `OrderStore` / `PublishStateStore` 落 Postgres | 后续（本次只做 catalog） |
| 编排链的超时机制 | 后续（本次明确允许长期挂起，§4.5） |
| "人工重新生成内容"（degraded 但 completed 的 Listing 重生成） | 后续；本次通过 §5 的硬断言把它转为失败信号（缺口已登记） |
| 采集层触发形态（爬虫 / 批量 API / 调度） | 既有 fog，本次只做 REST 单入口 |
| 真销售平台 Adapter（淘宝 / 拼多多 / 速卖通） | 后续（本次用 `adapter-fake`） |
| RabbitMQ 事件总线的实际接入（`EventPublisher` 仍为 `Noop`） | 后续；本规范不引入事件驱动链路（§4.4 用 child workflow 而非 Domain Event） |
| `projection` 读侧 / 看板 | 后续 |
| MCP transport | ADR-0009 已定后置 |
