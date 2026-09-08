# AI Step 可插拔抽象与多模型后端：内容链与铺货链分离 + Step 字段级契约 + LLMProvider SPI（编排插槽预留）

AI 能力契约定为：**内容链与铺货 execution 分离（两条链）**——AI 内容生产是独立 workflow 域（每 Listing 一次 `content-{listingId}`，清洗/翻译回填 master + 生成 Listing 改写稿/价格/媒体），产物 = Listing 内容就绪；铺货 workflow（#11 `listing-{id}`）只读就绪 Listing，重铺 ≠ 重生成（AI 长耗时/审稿/重跑与铺货幂等/重放语义正交，混用会破坏 #11 确定性）；**Step = 无状态插件、边界 = 标准模型字段级读写**——声明 `id/input/output/model_requirement`（LLM / 媒体处理 / 纯规则三分），**Java SPI 注册**（#9 同哲学：core 零 AI 平台依赖、无中央注册表），**不发明中间 DTO 流**（读标准模型写标准模型，单 Step 可重跑/可跳过/`provenance.step` 可审计，不设"清洗层/铺货层"概念——归属由读写哪层字段决定）；**模型后端 = LLMProvider SPI + OpenAI-compatible 契约主干**——core 定义 `chat(request)→response`（对齐 OpenAI chat/completions、HTTP 直连不引 SDK），`OpenAICompatProvider` 配 base_url/api_key/model 即覆盖 OpenAI/本地（Ollama/vLLM）/国产（通义/DeepSeek）绝大多数后端；媒体处理 ≠ LLM（独立 `MediaProcessor` 接口走机械处理，生成式 LOCALIZED variant v1 预留不实现 #7）；**平台自带 AI（速卖通 AIGC/Shopify Magic）不进 provider 体系**——research 校准为后台人工能力、无开放 API，v1 = 平台后台人工兜底，未来开放则经其 Adapter（#9）暴露；**模型编排插槽预留、v1 不做编排**（用户补充决议）：单模型难包揽全部任务，但编排价值需足够多 Step + 多样模型生态才显现，v1 = "单模型 + 可切换"，预留成本 ≈ 一个解析点 seam `model_requirement → (provider, model)`——v1 实现静态配置映射，未来编排器在同一解析点实现路由，core 契约与 Step 声明不改；**编排位置 = workflow 层**（#13：Step 序列 = Temporal 代码确定性编排，Activity RetryPolicy + `ScheduleToCloseTimeout` 兜总时长，管线状态 = Event History 不自建状态机）；**结果落库 = v1 单稿制**——AI 产物直写 Listing 内容字段（`provenance.step=AI`），无候选/采纳两态；人工 gate 自然发生点 = 铺货触发前预览编辑（`provenance.step=HUMAN`），不进审批流（候选稿增强后置于批量经营场景）；**失败 = Step 级降级而非阻断**——单 Step 重试耗尽 → 产物缺省降级（原文/默认加价率）+ Listing `degraded_steps` 记录供看板 HITL；硬依赖 Step（媒体/跨境翻译）失败 → 内容链 failed 走 #13 重放/告警（"不阻铺货" ≠ "没内容也硬铺"）；**成本语义 v1 极简**——无硬预算，控制面 = 调用前估算 + 可关闭 Step + provider 档位切换，token/成本落执行记录聚合，不建计费系统。背景：#7 已定 AI 产物锚点（改写稿→Listing、翻译→canonical i18n、图片→MediaAsset、LOCALIZED 预留），本票定"AI 服务"的形态化；research 显示平台 AI 全部后台化（仅 eBay Translation API 开放可调）、Medusa Workflow+Step 为同类范式、LLM 耗时与成本控制未覆盖（以编排层外部调用标准模式补齐）。细节见[规范 0006](../specs/0006-ai-step-model.md)。

## Considered Options

- **AI Step 嵌进铺货 workflow 前置 activity（一次铺货里先 AI 再 add）**（否决）：AI 长耗时 + 非确定性 + 需审稿/重跑，与铺货幂等/重放语义（#11）耦合——重铺会重跑 AI 产生新稿，确定性被破坏；内容链/铺货链分离使"修一次内容重铺 ≠ 重生成"成立，两条链各自独立演进。
- **Step 自建管线引擎（配置驱动序列、Step 间自管状态）**（否决）：重复造 Temporal 已给的编排轮子（确定性重放/RetryPolicy/超时兜底/Event History 即状态）；Step 保持无状态 + workflow 编排 = 状态机归属清晰，不引入第二套有状态运行时。
- **Step 之间传私有 DTO / 中间结构**（否决）：可插拔社区 Step 无法共享私有类型，耦合爆炸；字段级读写标准模型让 Step 组合自由（A 的输出 = B 的输入即同字段），中间结构 = 新契约面。
- **每平台一个 provider 直接适配（平台 SDK 进 core）**（否决）：平台 AI 无开放 API（research：后台化），SDK 直连无从谈起且污染 core；OpenAI-compatible 主干 + 特殊平台 SPI 独立模块，core 只认一个 `chat` 语义。
- **v1 即做模型编排/路由（多模型分派）**（否决，用户拍板）：编排价值需足够多 Step + 多样模型生态才显现，v1 不遇此复杂度；但预留解析点 seam 成本≈零——架构为编排留缝、v1 不背编排复杂度（"单模型 + 可切换"够用）。
- **候选稿/采纳稿两态 + 人工审批 gate 进内容链**（否决）：铺货本就是人工/调度触发（#11），"审稿"自然发生在用户点铺货前——两态与审批流是批量经营场景（AI 出 N 稿人工挑）的复杂度，v1 单稿制（AI 直写 + 人工覆盖改 provenance）足够；多版本 = 自建版本表（对齐 #8 不自建 append-only 史）。
- **Step 失败即内容链整体失败**（否决）：单 Step（标题改写/价格策略）失败就整链 failed 会让可铺货商品被无谓阻塞；降级语义（原文/默认价 + `degraded_steps` 标记 HITL）保证"单 Step 失败不影响整体铺货"，硬依赖 Step 才失败。

## Consequences

- 社区/自研 AI Step 以无状态插件接入：只需懂标准模型字段 + Step 接口 + model_requirement，不碰 Temporal/RabbitMQ/Spring 内部；Step 组合自由、可重跑、产物可审计。
- 铺货链确定性不受 AI 影响：内容链产物落库为终稿，铺货 workflow 只读——重铺、重放、幂等语义（#11）保持纯净。
- 模型后端接入成本 ≈ 配一个 base_url + api_key：OpenAI/本地/国产同协议全覆盖；平台 AI 不阻塞自动链（人工兜底），未来开放即经 Adapter 升级。
- 编排插槽已留（解析点 seam）：v1 静态映射零成本，未来编排器在同一接口实现路由，已注册 Step 与 core 契约无需迁移。
- 失败语义分层清晰：可降级 Step 不阻铺货（degraded_steps 看板 HITL）、硬依赖 Step 才 failed（走 #13 重放/告警）——AI 故障的影响面 = 内容增强缺失，不是铺货瘫痪。
- 成本零基础设施：估算 + 开关 + 档位即控制面，token 记录聚合供看板——不欠计费系统的债。
