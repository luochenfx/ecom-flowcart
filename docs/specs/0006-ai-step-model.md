# 规范：AI Step 可插拔抽象与多模型后端

> 来源：[Grilling: AI Step 可插拔抽象与多模型后端](https://github.com/luochenfx/ecom-flowcart/issues/12)（Part of #1）
> 依赖：[ADR-0004/#7（商品模型）](../adr/0004-product-catalog-model.md)（AI 产物锚点：改写稿→Listing、翻译→canonical i18n、图片→MediaAsset）、[ADR-0003/#11（铺货 workflow 只读 Listing）](../adr/0003-listing-idempotency-via-deterministic-workflow-id.md)、[ADR-0002/#13（有状态链进 Temporal）](../adr/0002-temporal-for-workflow-orchestration.md)、[ADR-0007/#9（Adapter 插件机制哲学）](../adr/0007-adapter-plugin-contract.md)（SPI/无中央注册表同源）。
> 状态：v1 设计期决议。契约的机器可读形态 = **Java 接口**（`Step` / `LLMProvider` / `MediaProcessor` / 模型解析点 seam，代码级，非数据/消息 payload），签名随本规范落盘、实现期落 core `step` 包；`schemas/` 目录保留给数据/消息契约（与 #9 同决策），不为 Step 造伪 schema。Listing 新字段（`degraded_steps` 等）**已随 #20 并入 product-catalog schema**（Java 侧 `Listing.degradedSteps` + `DegradedStep`，schema 侧 `$defs/DegradedStep`）。

## 1. 决策概览

- **内容链与铺货 execution 分离（两条链）**：AI 内容生产（清洗/翻译回填 master + 生成 Listing 改写稿/价格/媒体）独立于铺货 workflow。**内容链** = 每 Listing 一次内容准备（Temporal 编排，`content-{listingId}` 域），产物 = Listing 内容字段就绪；**铺货链** = #11 `listing-{id}` workflow 只读就绪 Listing。重铺 ≠ 重生成、重生成不触发铺货——AI 长耗时 + 审稿/重跑与铺货幂等/重放语义（#11）正交，混在一起会破坏确定性。
- **Step = 无状态插件，边界 = 标准模型字段级读写**：Step 声明 `id` + `input`（读哪些标准模型字段）+ `output`（写哪些字段）+ `model_requirement`（LLM / 图像服务 / 纯规则——价格策略可纯规则零模型）；**Java SPI 注册发现**（对齐 #9 Adapter 哲学：core 只认接口 + classpath 存在即被发现）；**不发明中间 DTO 流**——Step 之间不传私有对象，读标准模型、写标准模型（含 JSONB 属性容器），单 Step 可重跑/可跳过/产物可审计（`provenance.step`）。
- **模型后端 = LLMProvider SPI + OpenAI-compatible 契约主干**：core 定义 `LLMProvider.chat(request) → response`（OpenAI chat/completions 语义对齐），**不引 SDK**（HTTP 直连）；`OpenAICompatProvider` 配置 `base_url + api_key + model` 即覆盖 OpenAI/本地（Ollama/vLLM）/国产（通义/DeepSeek）绝大多数后端（皆兼容 OpenAI 协议）；特殊协议平台写独立 provider（SPI）。
- **媒体处理 ≠ LLM**：媒体 Step 走独立 `MediaProcessor` 接口（去水印/格式/尺寸，机械处理），生成式插槽预留（#7 `variant_purpose=LOCALIZED` v1 不实现生成）——媒体与 LLM 两类能力在 Step 抽象上分型（`model_requirement` 区分），不混在一个 provider 体系。
- **平台自带 AI（速卖通 AIGC / Shopify Magic）不进 provider**：research 校准为**后台人工能力、无开放可编程 API**（速卖通 AIGC 后台免费但无 OpenAPI spec）→ v1 = 平台后台人工兜底选项，内容链自动流程不依赖；未来某平台开放 API → 经其 Adapter 暴露（#9 Capability），不经 provider 直连。
- **模型编排插槽预留，v1 不做编排**（用户补充决议）：单一大模型难包揽所有任务，但编排价值需足够多 Step + 足够多样模型生态才显现——v1 只会遇到"单模型 + 可切换"。**预留成本≈一个解析点**：`model_requirement → (provider, model)` 的选择器 seam——v1 实现 = 静态配置映射（切换 base_url/model 即全局换模型）；未来编排器 = 在同一解析点实现路由逻辑（按 Step 类型/成本/质量分派），core 契约与 Step 声明不改。**架构为编排留缝、v1 不背编排复杂度**。
- **编排位置 = workflow 层**：内容链 Step 序列 = Temporal workflow 代码确定性编排（#13 延伸），单步重试/超时/重放 = Activity RetryPolicy + `ScheduleToCloseTimeout` 兜总时长（research：外部调用标准模式）；Step 无状态，管线状态 = workflow Event History（不自建管线状态机）。
- **结果落库 = v1 单稿制**：AI 产物直接写 Listing 内容字段（`provenance.step=AI`）；无"候选稿 → 确认 → 采纳稿"两态。人工 gate 的自然发生点 = **铺货触发前**（用户看 Listing 预览/编辑，改后 `provenance.step=HUMAN`），不进系统审批流。候选稿增强（AI 出 N 稿人工挑）后置于批量经营场景，模型解析点预留不改此数据语义。
- **失败 = Step 级降级（degrade）而非阻断（fail-fast）**：单 Step 重试耗尽 → 该 Step 产物缺省降级（标题改写失败 → 用清洗后原文；价格策略失败 → 默认加价率 + 标记复核），Listing 记录 `degraded_steps[{step, reason}]` 供看板 HITL；**硬依赖 Step**（媒体处理失败 = 无图不能铺；翻译失败 = 跨境无内容）→ 内容链 failed 走 #13 重放/告警——"不阻铺货" ≠ "没内容也硬铺"。
- **成本语义 v1 极简**：无硬预算限额——成本控制面 = 调用前估算展示 + 可关闭指定 Step（配置）+ provider 档位切换；每次调用 token 用量/成本落执行记录（provenance 延伸）供看板聚合；**不建独立计费/成本系统**。

## 2. 两条链：内容链与铺货链

```
采集 → SPU master（raw 落库）          （#7）
         │
   内容链 workflow（每 Listing 一次，content-{listingId}）
         ├─ 翻译回填 canonical i18n（master 侧，一次翻译多处复用）
         ├─ 标题/描述改写稿 → Listing（AI Step，model_requirement=LLM）
         ├─ 价格策略 → Listing SKU 售价集（AI/纯规则 Step）
         └─ 媒体处理 → MediaAsset processing_state（MediaProcessor）
         │
   Listing 内容就绪（provenance.step=AI/HUMAN，degraded_steps?）
         │
   铺货 workflow（#11：listing-{id}，只读就绪 Listing，幂等/重试/reconcile）
```

- 内容链触发源：商品入库后自动（master 就绪事件）或人工"重新生成内容"——触发即 start content workflow（确定性 workflowId 幂等，对齐 #11 哲学）。
- 清洗层与铺货层"各调哪些 Step"：**不设层概念**——Step 归属哪侧由它读写哪层字段决定（读/写 master/canonical = master 侧；读/写 Listing = Listing 侧），编排配置按需选取。

## 3. Step 抽象契约

签名草图（实现期落 core `step` 包，语义先行）：

```
interface AiStep {
  StepDescriptor descriptor();              // id/input/output/model_requirement/参数 schema
  StepResult execute(StepContext ctx);       // 读标准模型字段 → 处理 → 写回字段
}

StepDescriptor {
  String id;                                 // 如 "title.rewrite" / "desc.generate" / "media.process" / "price.strategy"
  FieldRef[] input;                          // 读：listing.title / spu.title_i18n / media[] ...
  FieldRef[] output;                         // 写：改写稿字段 / canonical i18n 回填 / MediaAsset / Listing SKU 价
  ModelRequirement modelRequirement;         // LLM | MEDIA_PROCESSOR | RULE（纯规则零模型）
  JsonSchema params;                         // 步骤参数（温度/指令模板/加价公式…）
}
```

- **注册发现 = Java SPI**（`AiStepProvider` 声明实现清单），与 #9 Adapter 插件机制同哲学：core 零 AI 平台依赖、Step 模块独立演进、无中央注册表。
- **确定性约束**：Step 必须无状态、输出只由输入 + 参数决定（workflow 重放安全）；LLM 非确定性由内容链"产物落库为终稿、重跑才覆盖"吸收（不追求同输入同输出）。
- `provenance.step` 已由 #7 定义枚举（CAPTURE/CLEAN/AI/LISTING/RECONCILE/HUMAN）——Step 产物写库时标记。

## 4. 模型后端适配（LLMProvider + 解析点 seam）

```
interface LLMProvider {
  ProviderId id();                           // "openai" / "local-ollama" / "dashscope" ...
  ChatResponse chat(ChatRequest req);        // OpenAI chat/completions 语义对齐（HTTP 直连，不引 SDK）
}

// v1 唯一内置实现：
class OpenAICompatProvider implements LLMProvider {
  // base_url + api_key + model 配置 → 覆盖 OpenAI / Ollama / vLLM / 通义 / DeepSeek ...
}

// 解析点 seam（用户补充决议：编排插槽预留，v1 只做静态映射）：
interface ModelResolver {
  ResolvedModel resolve(ModelRequirement req, StepContext ctx);
  // v1 实现 = 静态配置映射（model_requirement + stepId → 固定 provider/model）
  // 未来 = 编排器（按 step 类型/成本/质量/可用性路由多模型），本接口即插槽，core/Step 声明不改
}
```

- 特殊协议平台（未来需要）→ 独立 provider 实现 + SPI 注册；core 不逐平台适配。
- provider 凭据管理：走 #9 channel credential 同源（AES 落库 + CredentialView），provider 是系统级凭据（非 channel 级）——实现期并入 CredentialStore 的凭据类型体系。

## 5. 编排（workflow 层）与失败降级

- 内容链 workflow 定义 Step 序列（翻译 → 改写 → 价格 → 媒体），每步一个 activity；RetryPolicy 按 Step 语义配置（LLM 超时重试有限次，`retryable_after` 参考 #9 限流自治）。
- **单 Step 重试耗尽 → 降级分支**（workflow 代码表达，非异常中断）：
  - 降级可用：该 Step 产物缺省（原文/默认加价率），写 `degraded_steps`，内容链继续；
  - 硬依赖 Step 失败：内容链 failed（Listing 不进铺货队列），**发 `sys.workflow.failed` 事件（#10）告警**——见下方"硬失败事件"段。事件 payload = Temporal workflowId + runId + 失败 Step + 原因，不依赖业务库行。
- 幂等/重入：内容链重跑 = 同 workflowId 新 run 覆盖内容（对齐 #11 `AllowDuplicateFailedOnly` 哲学）；与铺货 workflow 互不触发。
- **硬失败事件（`sys.workflow.failed`，A-prime 降级语义，#20 拍板）**：activity 在抛回 `ContentChainFailedException` 给 Temporal 之前，先通过 `worker.event.EventPublisher.publishFailed(SysWorkflowFailedEvent)` 发一个事件。**承认极少数情况下通知可能丢失**——例如 process 在落库后、发事件前 crash。**对冲方案**：消费方需配合 Temporal UI 巡检兜底（Temporal event history 是真相源，`sys.workflow.failed` 仅是 signal）。事务基建（落库与发事件同事务）属于 #22 publish 跨域基建，#20 不引入。

## 6. 结果落库（单稿制）

| 产物 | 落点 | 标记 |
|---|---|---|
| 改写标题/描述 | Listing 内容字段 | `provenance.step=AI` |
| 翻译（多语言） | master canonical i18n 回填 | `provenance.step=AI` |
| 价格策略结果 | Listing SKU 售价集 | `provenance.step=AI\|RULE` |
| 媒体处理结果 | MediaAsset `processing_state` | 资产状态轴 |
| 人工编辑覆盖 | 同字段 | `provenance.step=HUMAN` |
| 降级记录 | Listing `degraded_steps[{step, reason}]` | 看板 HITL |

- 无候选/采纳两态、无版本表：旧稿可溯 = Temporal Event History（对齐 #8 哲学）。

## 7. 成本语义（v1 极简）

- 调用前估算（token 粗估 × 单价）在内容链触发时展示/记录；可关闭指定 Step；provider 档位（base_url/model）切换 = 成本控制面。
- 执行记录（provenance 延伸）：每次 Step 调用的 model/token 用量/估算成本落内容链执行记录，看板聚合。不建计费系统。

## 8. 首批 Step 清单（v1）

| Step id | 读写 | model_requirement | 失败降级 |
|---|---|---|---|
| `i18n.backfill`（翻译回填） | SPU canonical i18n | LLM | 硬依赖（跨境）；国内链可跳过 |
| `title.rewrite`（标题改写） | Listing 改写稿 | LLM | 用清洗后原文 + degraded |
| `desc.generate`（描述生成） | Listing 描述改写稿 | LLM | 用原文 + degraded |
| `price.strategy`（价格策略） | Listing SKU 售价集 | RULE（可升 LLM） | 默认加价率 + degraded |
| `media.process`（媒体处理） | MediaAsset | MEDIA_PROCESSOR | 硬依赖（无图不能铺） |
| `media.localize`（图内文本本地化） | MediaAsset variant（LOCALIZED） | 生成式 | **v1 预留不注册**（#7：成本下降后引入） |

## 9. 与既有契约的对齐

- #7：产物锚点全部消费（改写稿→Listing、翻译→canonical、媒体→MediaAsset、provenance.step=AI）；LOCALIZED 生成式 v1 不实现（`media.localize` 预留不注册）。
- #11：铺货 workflow 只读就绪 Listing——内容链是它的上游生产者，两条 workflow 分离（重铺不重生成）。
- #13：内容链 = Temporal 有状态链（每 Listing 一 execution）；失败分类（降级/workflow failed/重放）走既定原语。
- #9：Step 插件机制 = Adapter SPI 同哲学；provider 凭据并入 CredentialStore。
- #10：内容链产物不广播；`sys.workflow.failed`（#10 首批事件）承载内容链失败告警。

## 10. 遗留 fog（实现期回填）

- ~~Step 参数 schema 的具体形态~~ → **已回填（#20）**：v1 = Jackson `JsonNode`（`StepDescriptor.params()`），配**显式字段路径白名单**（`ListingStepContext`：`spu.titles` / `spu.descriptions` / `spu.skus` / `listing.title_overrides` / `listing.description_overrides` / `listing.locales` / `listing.sku_set` / `media`）。越界路径立即 `IllegalArgumentException`，不静默返回 null——契约面小才可审计。不引 JsonSchema 引用 / Java bean 注解。
- ~~LLM 调用超时 / 重试档位~~ → **已回填（#20）**：activity 侧 `StartToCloseTimeout=2min`、`RetryOptions.maximumAttempts=1`。**单次尝试是有意的**：硬依赖失败要让内容链 failed 走重放 / 告警，而不是占着 workflow 槽位无限退避；要吃掉瞬时抖动就在 `ContentActivityOptions` 一处调大 attempts，Step 与 workflow 都不动。
- ~~token 估算方法~~ → **已回填（#20）**：**不本地估算**，直接用 provider 回报的 `usage`（`ChatUsage` → `ContentStepRun.totalTokens()`）。provider 未回报 usage 时为 null（诚实缺省，不假装有数）。
- 单价 / 成本折算（token → 金额）仍**未定**：v1 无计费系统，只落 token 用量供看板聚合；接入真实单价表时在 `ContentStepRun` 之外另立，不改 Step 契约。
- **内容链幂等策略 vs「人工重新生成内容」触发（#20 实现时暴露，待决）**：§9 已定内容链沿用 #11 的 `WorkflowIdReusePolicy = AllowDuplicateFailedOnly`。#20 实测其字面后果：**一个「跑完但降级」的 Listing（`degraded_steps` 非空、workflow 仍为 completed）无法用同一 workflowId 重新生成内容**——它命中"前一次 completed → 拒绝新 run"（幂等复用，返回既有 execution 结果）。而 §2 把"人工重新生成内容"列为内容链的**合法触发源**。二者冲突，须在拥有该触发源的 slice（`api` 触发面 / #21）上收敛，二选一：
  - **A（保持现状，保守）**：接受缺口——重跑仅限 failed；"重新生成内容"需另设 workflowId 口径（如在 id 里带 generation 计数），代价是这条路要新建编排入口。
  - **B（放开终结后重跑）**：改 `WorkflowIdConflictPolicy = FAIL` + `WorkflowIdReusePolicy = ALLOW_DUPLICATE`——仍拒**并发**重复，但放行**终结后**重跑。代价：同一 Listing 的**连续误触**（相隔较久的第二次误触发）会真的再烧一次 token。
  - ⚠️ 本票（#20）按已文档化的 A 实现，**未擅自放宽**；B 属回归本行时必须显式改 `ContentWorkflowLauncher.optionsFor` 一处。
- **「内容就绪态」不落字段，承认推导量语义（#20 实现拍板的正式决议）**：AC-1 字面要求"收敛为内容就绪态"，可解读为两条：(A) 加 `Listing.contentReady` 持久化字段；(B) "就绪"= workflow 终态推导量（workflow 跑完 + 返回结果 = 就绪；硬依赖失败走 workflow failed，无返回 = 未就绪）。**采纳 B**（与 #21 publish 消费对齐：内容链终态事件触发铺货，不读持久化字段）。理由：(1) 加 `Listing.contentReady` 会与 workflow 终态构成两个真相源——一旦不同步（罕见但真实的并发写），运营与铺货消费各执一词立刻失语；(2) 当前 `ContentWorkflowResult.contentReady()` 死方法（恒 true）正是该设计的反射教训，#20 实现期一并删；(3) `degraded_steps` 已承载"未完全就绪但可铺"的缺口语义，看板 HITL 已有触发面。**对应的 Java 契约**：`ContentWorkflowResult.contentReady()` 方法删除（恒 true 占位 = 第二个真相源）；`ContentChainResult.contentReady` 字段保留（恒 true 构造语义不变，javadoc 改写说明推导量本质）。

## 11. 实现落点（#20，只记位置不重述语义）

| 关注点 | 落点 |
|---|---|
| 单步失败语义（降级 vs 硬失败，唯一判定点） | `content` `ContentStepExecutor` |
| 进程内顺序编排（测试 / demo / 非 Temporal 调用方） | `content` `ContentChainService` |
| 首批 5 Step 实现 + SPI 声明（无参构造即生产默认装配） | `content` `ai/` + `spi/ContentAiStepProvider`（`META-INF/services`） |
| LLMProvider SPI + OpenAI-compatible HTTP 直连 | `content` `provider/OpenAICompatProvider`（`base_url` / `api_key` / `model` / `timeout_seconds`） |
| ModelResolver seam（v1 静态映射） | `content` `resolver/StaticModelResolver` |
| 单稿制物化 + provenance 盖章 + degraded 留痕 | `content` `model/ContentWorkingSet`（`toDocument`） |
| Listing 装配（内容链输入建单口） | `content` `listing/ListingDraftFactory` |
| 人工编辑覆盖（AI → HUMAN） | `content` `listing/HumanContentEdit` |
| Temporal 编排壳（`content-{listingId}` workflow / 每步一 activity） | `worker-runtime` `ContentWorkflowImpl` + `ContentChainActivitiesImpl` + `ContentWorkerFactory` |
| 幂等 / 启动口径（确定性 workflowId + 复用策略） | `worker-runtime` `ContentRuntime.workflowIdFor` + `ContentWorkflowLauncher.optionsFor` |
| 降级留痕的读改写（本次判定覆盖历史） | `content` `ContentWorkingSet.addDegradedStep` / `clearDegradedStep`（执行器单点调用） |

