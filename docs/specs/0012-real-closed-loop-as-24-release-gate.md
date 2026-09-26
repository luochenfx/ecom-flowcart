# 规范 0012：1688→淘宝真实闭环作为 #24 发布门禁（两段式）

> 来源：Grilling session（2026-09-27），议题「#24『发布准备』放行判据重定义」。决策记录：DR-closed-loop-gate-001（`.decisions/1688-to-taobao-closed-loop-gate-decision-log.md`）。
> 依赖：[ADR-0003（铺货幂等）](../adr/0003-listing-idempotency-via-deterministic-workflow-id.md)、[ADR-0007（Adapter 插件契约）](../adr/0007-adapter-plugin-contract.md)、[规范 0001（铺货幂等）](./0001-listing-publish-idempotency.md)、[规范 0005（Adapter 契约）](./0005-adapter-plugin-contract.md)、[规范 0007（端到端链路装配）](./0007-end-to-end-flow-assembly.md)。
> 状态：v1 决议。**本规范定义门禁判据，不承诺实现**——真实淘宝销售 Adapter 与 1688 签名网关 offer 端点**均尚未实现**（缺件见 §3.3，逐条显式标注）。
> 范围声明：本规范只解决「#24 何时可开启 / 何时可对外宣称真实闭环」。**不**解决真实平台接入的工程实现（那属对外段 §5 前置的两条链）。

## 1. 决策概览

- **门禁改为两段式（候选 d）**：#24 拆为「**内部准备段**」与「**对外发布段**」。内部准备段**立即放行**，判据 = README 描述不得超前于实际能力；对外发布段（即"对外宣称端到端/真实闭环已打通"）**须真实闭环证据包**方可开启。
- **真实闭环的内核完整保留**：候选 b 的内核「真实闭环 = 对外发布硬前提」**完整并入** d 的对外段；被否决的只是「冻结整张 #24」这一形态。
- **内部准备段不依赖资质**：其判据全部落在文档措辞与元数据清理，**不依赖 Q1/Q2（淘宝/1688 资质）**。
- **对外发布段前置 = 两条未落地的链**：① 1688 采集接 **param2 签名网关**（向 `Ali1688Api` 新增 offer 端点 + appKey/appSecret/access_token）；② **真实淘宝销售 Adapter**（新 Maven 模块，`app/pom.xml` main scope 引入）。二者**当前均不存在**。
- **契约层零改动（关键结论）**：「上架成功」的充分且唯一标志**已存在**——`PublishCapability.add(Listing) → PublishResult.platformItemId`（`core-contracts/.../PublishCapability.java:20`），`PublishService.recordAddOutcome`（`publish/.../PublishService.java:192-199`）在 `add()` **同步成功返回**时即落 `PublishStatus.PUBLISHED`。**缺失的是"可达层"（真实淘宝 Adapter 实现），不是"信号定义"** ⇒ 不触发 `AGENTS.md` 的 core 契约变更闸门。
- **降级备选（候选 c）常备**：对外段长期不可达时，退为「分级门禁 + 显式声明真实闭环未验证」，见 §6。
- **候选 a（维持现状 `Blocked by #22/#23`）淘汰**：#22/#23 均已 **CLOSED**，门禁链接 stale 且实际失效（§10 证据 E6）。

## 2. Problem Statement

`#24` 原放行判据 = `Blocked by: #22 order、#23 1688 Adapter 全能力收口`。当前该判据已**双重失效**：

1. **门禁链接 stale 失效**：#22、#23 均已 CLOSED（`gh issue view 22/23`），`#24` body 仍声明被二者阻塞——阻塞语义已不成立，却无人放行，形成"僵尸门禁"。
2. **门禁与真实能力无关**：#22 交付形态是 **fixture adapter demo**（订单域），#23 交付的是 **货源侧**（1688 的 `{OfferFetch, Purchase, Auth}`）能力——二者**都不涉及**"真实销售平台上架"这一 #24 目标所隐含的对外承诺面。

同时，仓库已做出**超前宣称**：`README.md:7` 断言「**端到端链路已打通**」，而其 `README.md:133` 自述的口径实为「编排链 + 装配根 + catalog 落 Postgres + **`adapter-fake` 测试 Adapter** + e2e 验收」——即"端到端"一词被**过载**：README 指"链路装配（fake 边界）"，用户指"真实平台闭环"。此过载是本次门禁争议的根因（域术语需消歧，见 §7-U5 与 §11 的 CONTEXT.md 连带项）。

**真实闭环缺件清单（一手复算）**见 §3.3。

## 3. Solution：两段式门禁（候选 d）

### 3.1 内部准备段（**放行判据，不依赖 Q1/Q2**）

**硬约束**：README 描述不得超前于实际能力（即 `#24` 现有 AC4）。**三条可观测判据**：

1. **`README.md:7` 措辞降级为显式限定**：把"端到端链路已打通"改写为"编排链装配 + `adapter-fake` 测试 Adapter 验收（非真实平台闭环）"，与 `README.md:133` 已有表述自洽。
2. **增列真实闭环缺件**：显式声明当前**无真实销售平台 Adapter**（`pom.xml:39-50` 无 adapter-taobao；生产 classpath 无 `PublishCapability`——`adapter-fake` 为 `app/pom.xml:86-91` test scope）、**1688 采集未接签名网关**（`Ali1688OfferFetch.java:20-24` 仅 `source_ref.url` 直连）、**无真实平台凭据**（`.env.example:1-19` 无 1688/淘宝项）。
3. **AC2 措辞标注**：`#24` AC2「示例数据……5 分钟内跑通端到端 demo」须标注为**"基于测试 Adapter"**。

**清理项**：删除 `#24` body 中 stale 的 `Blocked by: #22, #23`，并把"对外发布"独立为**子门禁**（§3.2）。

### 3.2 对外发布段（**证据包判据**）

对外发布 = 任何"宣称端到端/真实闭环已打通"的对外动作（发布公告、Roadmap 打勾、README 恢复无保留表述等）。**过判据 = (a)+(b) 两证据齐**：

- **(a) 严基线：经 1688 官方 param2 签名网关采集成功**
  - 落地要件：向 `Ali1688Api` 枚举**新增 offer 端点**（当前仅 4 个 trade/logistics 端点，`Ali1688Api.java:12-34`）+ appKey/appSecret/access_token + `_aop_signature`（算法已在 `Ali1688Signature.java:12-32,57-68` 落地，复用即可）。
  - 证据 = 外部调用日志（含签名因子）+ `catalog_product` 表存在该 SPU 行。
- **(b) 仅同步信号：真实淘宝 Adapter 的 `add()` 同步返回真实 platform_item_id**
  - 落地要件：**真实淘宝销售 Adapter 实现**（当前不存在）。
  - 证据 = `PublishStateStore` 该 listingId 落 `PublishStatus.PUBLISHED`（`PublishService.java:192-199`）+ platform_item_id 非空。

### 3.3 真实闭环缺件清单（一手复算，全部为「未实现」）

| # | 缺件 | 证据（一手） | 归属段 |
|---|---|---|---|
| 1 | 无任何真实销售平台 Adapter | `pom.xml:39-50` 无 adapter-taobao 模块；`docs/specs/0007:342`「全仓没有任何销售平台 Adapter」；`grep Taobao/淘宝 --include=*.java` 仅注释/文档 | 对外段 |
| 2 | 生产 classpath 无 `PublishCapability` | `app/pom.xml:78-82`（adapter-1688 为 main scope，但**不含 Publish**）+ `:86-91`（adapter-fake 为 **test scope**）⇒ 生产态 `AdapterHost` 无从发现销售能力 | 对外段 |
| 3 | 1688 采集未接签名网关 | `Ali1688OfferFetch.java:20-24,48-64`（POST `source_ref.url`，无签名/无凭据）；`Ali1688Api.java:12-34` 无 offer 端点 | 对外段 |
| 4 | 无真实平台凭据 | `.env.example:1-19` 仅 postgres/rabbitmq/媒体根/备份，无 1688/淘宝项 | 对外段 |
| 5 | 内容链真实 LLM 端点未接入 | e2e 用 WireMock 固定端口 stub（`FlowAssemblyE2EIT.java:110-115`）；生产靠 `FLOWCART_LLM_BASE_URL` 配置（`未复算` 生产注入全路径） | 对外段 |

## 4. 内部准备段（放行判据 · 细化）

**放行条件**：下列三条判据**全部满足**即可开启/继续 #24 的**内部准备**工作（README 完善 / 示例数据 / 贡献指南）。三条均**不依赖** Q1/Q2（资质、成本）。

**判据 I-1 —— README 描述降级（核心）**
- 口径：`README.md:7` 的"端到端链路已打通"必须**限定为"链路装配 + 测试 Adapter 验收"**，不得保留可能被读作"真实平台闭环"的无保留表述。
- 目标措辞（改写方向，落地时按仓库文风定稿）：「**端到端链路已打通（编排链装配口径）**：编排链 + 装配根 + catalog 落 Postgres 均已交付；铺货边界由 **`adapter-fake` 测试 Adapter** 承担——**真实 1688 采集与真实淘宝上架尚未实现**（见"真实闭环缺件"）。」
- 依据：`README.md:7`、`README.md:133`、`docs/specs/0007:342,344-352`。

**判据 I-2 —— 增列"真实闭环缺件"**
- 口径：在 README（或指向本 spec）显式声明当前缺件清单（§3.3 表 1–5）。
- 依据：§3.3 各条一手证据。

**判据 I-3 —— AC2 措辞标注**
- 口径：`#24` AC2「示例数据：可导入的 seed / fixture（SPU / Listing / 订单示例）5 分钟内跑通端到端 demo」→ 增注「（**基于测试 Adapter**）」。
- 依据：`gh issue view 24` AC 段。

**清理项 C-1 —— 删除 stale 门禁链接**
- 口径：删除 `#24` body `## Blocked by` 段的 `#22`、`#23` 两行（二者 CLOSED），改为指向 §3.2 对外发布子门禁。
- 依据：`gh issue view 22/23`（均 CLOSED）+ `gh issue view 24`（body 仍含 `Blocked by: #23, #22`）。

## 5. 对外发布段（证据包判据 · 细化）

**开启条件**：内部准备段判据 I-1/I-2/I-3 已满足 **且** 下列证据包 **(a)+(b) 齐备**。

- **(a) 真实 1688 采集（严基线）**
  - 必需：`Ali1688Api` 新增 offer 端点（param2 命名空间 + 接口名）+ 系统参数 `appKey`/`appSecret`/`access_token`/`_aop_timestamp` + `_aop_signature`。
  - 判据：一次真实调用成功，产出一份含**签名因子**的外部调用日志；且 `catalog_product` 表存在对应 SPU 行（`PostgresCatalogStore`，见规范 0007 §8）。
  - 当前状态：**未实现**（`Ali1688Api.java:12-34` 无 offer 端点；`Ali1688OfferFetch.java:20-24` 仅 `source_ref.url` 直连）。
- **(b) 真实淘宝上架（仅同步信号）**
  - 必需：真实淘宝销售 Adapter（新 Maven 模块，实现 `PublishCapability`，`platform` = 淘宝；`app/pom.xml` 以 main scope 引入）。
  - 判据：`add()` **同步返回**非空真实 platform_item_id → `PublishStateStore` 该 listingId 落 `PublishStatus.PUBLISHED`。
  - 当前状态：**未实现**（无 adapter-taobao 模块）。

**过判据**：(a) 与 (b) 两证据齐 = 对外发布段可开启。

**残余语义边界（如实登记，不由本 spec 消解）**：若真实淘宝发布天然异步（item 创建 ≠ 前台在售），Adapter **仍须在"同步返回 platform_item_id"口径下工作**（阻塞至拿到 id）。本决策按用户明示**接受"创建即成功"语义、不做在售校验**（见 §8）。

## 6. 迁移规则（降级备选 c）

**触发条件**：对外发布段前置（资质不可得 / 成本不可接受 / 两条链工程量超出预算）**长期不可达**。

**降级动作**：门禁退为**分级门禁（候选 c）**——
- 采集卡与上架卡**分别**独立判定；已达成的卡可先行声明（如"真实采集已验证"）；
- 未达成部分**必须显式标注"真实闭环未验证"**，不得以任何无保留措辞对外宣称闭环；
- 对外发布段**保持关闭**，直至 (a)+(b) 齐或用户明确改判。

**回退性**：本迁移**可逆**——资质一旦具备、两条链落地，(a)+(b) 齐即恢复对外发布段。

## 7. 未决项（承 DR 清理结果）

| 编号 | 原状 | 处置 | 说明 |
|---|---|---|---|
| U1 | Q1 命题边界 | **关闭** | 已由候选 d 定案（门禁重定义 + 范围隔离到对外段） |
| U3 | Q3 真实采集基线 | **关闭** | 已定为严基线（param2 签名网关） |
| U4 | Q4 上架成功信号 | **关闭** | 已定为仅同步信号（`add()` 返回 platform_item_id） |
| U2 | Q2 淘宝资质 | **转态** | 本 spec 范围内 **non-blocking**；对外段落地时 **blocking**（不可得 → 退 c）。登记：待调研「淘宝开放平台商品发布类 API / 卖家电子面单通道」获取路径与成本，及"新增 1688 签名网关 offer 端点 + 真实淘宝 Adapter"两条链工程量。**本节不构成新 need_human** |
| U5 | `README.md:7` 措辞 | **升级** | → 内部准备段交付项（判据 I-1） |
| U6 | `#24` blocked-by stale | **升级** | → 内部准备段清理项（C-1） |
| U7 | e2e 命令 | **保留** | 随对外段另建"真闭环"验收命令；现 e2e（`FlowAssemblyE2EIT.java:110-115`）不动 |
| U8 | 真实 LLM 端点 | **保留** | `.env.example` 无凭据入口 |
| U9 | 生产态销售 Adapter 交付路径 | **保留** | 新增 Maven 模块 + `app/pom.xml` main scope |

## 8. Out of Scope（明确不做）

- **在售校验（审核/前台可见性）**：本决策**明示豁免**——"上架成功"以 `add()` 同步返回 platform_item_id 为充分且唯一标志，**不做**平台侧异步审核/在售二次确认（§5 残余语义边界）。
- **真实平台接入的工程实现**：新增 1688 offer 端点、真实淘宝 Adapter 实现、真实凭据接入、真实 LLM 端点接入——**不在本 spec**（归对外段前置的两条链，另行立项）。
- **#22/#23 的既有交付形态**：不追溯、不改写。

## 9. Testing Decisions

本 spec 为**门禁判据**规范，不新增代码，故不新增测试基线。既有约束沿用：
- 内部准备段判据 I-1/I-2/I-3 为**文档一致性**判据，验收 = 人工核对（无机器门）。
- 对外发布段 (a)/(b) 证据为**运行证据**（外部调用日志 + 落库事实），验收 = 一次真实运行的证据包，非自动化测试（真实平台不可 CI 化）。

## 10. 证据引用清单

| # | 主张 | 证据（一手复算） | 状态 |
|---|---|---|---|
| E1 | 无真实销售平台 Adapter | `pom.xml:39-50`；`docs/specs/0007:342`；`grep Taobao --include=*.java` 仅注释 | 已复算 |
| E2 | 生产 classpath 无 Publish 能力 | `app/pom.xml:78-82,86-91`（adapter-fake = test scope） | 已复算 |
| E3 | 1688 采集未接签名网关 | `Ali1688OfferFetch.java:20-24,48-64`；`Ali1688Api.java:12-34`；`Ali1688Signature.java:12-32,57-68`（算法已就绪） | 已复算 |
| E4 | 无真实平台凭据 | `.env.example:1-19` | 已复算 |
| E5 | 「上架成功」信号已存在 | `PublishCapability.java:20`；`PublishService.java:192-199`；`PublishStatus.java:17-26`；`PublishDisposition.java:12-38` | 已复算 |
| E6 | #22/#23 均 CLOSED、#24 门禁 stale | `gh issue view 22`（CLOSED）、`gh issue view 23`（CLOSED）、`gh issue view 24`（blocked-by 仍列 #22/#23） | 已复算 |
| E7 | README 超前宣称 | `README.md:7`；`README.md:133`（另见 `README.md:76`「端到端验收」命令注释，同型漂移） | 已复算 |
| E8 | e2e 用测试 Adapter + WireMock | `FlowAssemblyE2EIT.java:110-115,212,285-288`；`specs/0007:344-352,370` | 已复算 |
| E9 | 淘宝个人受限 | map #1 正文索引 research #2（原文落 `research/` branch，**未复算原文**） | **未复算** |

## 11. 连带项（本 spec 落地附带的文档消歧）

- `CONTEXT.md` 新增术语条目「**真实闭环（real closed loop）**」，与既有「测试 Adapter」条目（`CONTEXT.md:99-101`）配套，消解"端到端"过载（§2 根因）。
- `docs/architecture.md` §3 增补「销售平台 Adapter 现状」标注（无真实销售 Adapter / 生产态无 `PublishCapability`）。
- `README.md`：状态段（`:7`）、Roadmap（`:133`）与 e2e 命令注释（`:76`）三处"端到端"口径统一加限定。
