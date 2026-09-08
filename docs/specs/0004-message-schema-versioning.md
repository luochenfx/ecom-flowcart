# 规范：消息 Schema 分层与版本管理

> 来源：[Grilling: 消息 Schema 分层与版本管理](https://github.com/luochenfx/ecom-flowcart/issues/10)（Part of #1）
> 依赖：[ADR-0001（RabbitMQ v1 总线）](../adr/0001-rabbitmq-quorum-as-v1-message-bus.md)（envelope transport-agnostic，细节归本票）、[ADR-0002（Temporal）](../adr/0002-temporal-for-workflow-orchestration.md)、[ADR-0003/#11（铺货链入口不经总线）](./0001-listing-publish-idempotency.md)、[ADR-0005/#8（订单同步拉取为真相）](./0003-order-model.md)。
> 契约草案：[`schemas/message.schema.json`](../../schemas/message.schema.json)（v0.1.0）
> 状态：v1 设计期决议，随实现（producer/consumer 落地）与 #9（Adapter 契约）消费。

## 1. 决策概览

- **总线职责 = 窄总线**：RabbitMQ 只承载**已落库业务事实的领域事件**（广播：一写多读，看板/通知/审计/未来 ERP）。一切"驱动业务动作"的请求（采集→建 SPU、铺货、下采购单）**不经总线**——用 Temporal workflow start/signal 或内部调用（对齐 #11 铺货链入口不经总线、#13 有状态链进 workflow）。
- **推翻"三层 lane"假设**：原始（raw）→ 清洗（cleaned）→ 业务事件的物理分层不成立——平台 raw 负载落库（`platform_raw` JSONB / MediaAsset 已覆盖 #7/#8），加工在 workflow 内部、落库即终点。总线只有 **Domain Event 一层**，分层体现在**事件类型体系**（order.* / listing.* / purchase.* / rma.* / sys.*），不是三条物理 lane。旧表述"采集层统一标准化 JSON 进中间件"正式修订为"**标准化 JSON 落库、领域事件进总线**"。
- **envelope = transport-agnostic 单信封**（对齐 ADR-0001）：`id/type/version/occurred_at/producer/entity_ref?/correlation_id?/trace_id?/payload`，与 CloudEvents 字段对齐但自研轻量、不引 SDK。
- **版本策略 = repo 即 registry + 兼容性双轨**：schema 文件 = `schemas/events/<type>.schema.json`（当前单体草案），git 即版本管理、变更走 PR；**additive → bump version 次版本（新旧共存）；breaking → 新 type（旧 type 冻结）**。
- **事件类型全事件化（过去时），无命令态**：`<domain>.<entity>.<past-tense>`（`order.paid`/`listing.published`）。命令/请求意图不进 type 体系；跨 workflow 强信号走 Temporal Signal（非总线）。
- **关联追踪三级 id 正交**：`id`（消息唯一，幂等锚）/ `correlation_id`（业务链根）/ `trace_id`（技术执行链）；跨实体血缘靠 payload 内 `entity_ref` 逐跳。
- **DLQ = 重放素材 + 信号事件两层**：队列里的 DLQ 消息 = DeadLetterEnvelope（原 envelope 完整包裹，供重放）；广播 `sys.message.dead_lettered` = 信号（告警/看板）。绝无自动重投（ADR-0001）。
- **失败分类边界**：业务失败（Retryable/NonRetryable/Ambiguous）在 workflow activity 层（RetryPolicy/Saga/reconcile，#11），**不进 DLQ**；DLQ 只接投递/消费层失败（反序列化/校验/消费者致命异常）。

## 2. envelope 契约

```
{
  id            UUID           # 消息唯一（幂等消费锚）
  type          <domain>.<entity>.<past-tense>
  version       "1.2"          # payload schema 语义化版本（additive 次版本）
  occurred_at   date-time      # 业务事实发生时间
  producer      string         # workflow type / service 名
  entity_ref?   {type, id}     # 本消息关联业务实体（回读业务库）
  correlation_id? string       # 业务链根 id
  trace_id?     string         # 技术执行链（RunId 派生）
  payload       object         # 轻量：引用 + 变化摘要；真相在 DB，负载不作 first write
}
```

- 字段与 CloudEvents（id/source/type/time/data）对齐但更贴近本域（entity_ref/correlation/trace 为业务追踪显式化）；不引 CloudEvents SDK（v1 无桥接诉求）。
- **消费端先校验后业务**：反序列化失败/校验不过 = 永久毒消息 → 直接 DLQ 不重试；业务 handler 异常由所属层判定（workflow activity → RetryPolicy；无状态消费者 → delivery 重试直至 DLQ）。

## 3. 事件分类与命名（全事件化）

- **Domain Event**（业务事实广播）：`order.paid` / `listing.published` / `listing.ambiguous`（挂起 HITL）/ `purchase.shipped` / `rma.closed` … 命名 `<domain>.<entity>.<past-tense>`。
- **Lifecycle Event**（执行/投递层状况）：`sys.workflow.failed` / `sys.message.dead_lettered` … 与 Domain Event 共用同一 envelope，仅 type 前缀（`order.*` vs `sys.*`）区分，不建两套信封。
- **无命令态**：request/retry 意图不出现。铺货/采购等驱动动作 = 直接调 workflow；workflow 间级联强信号 = Temporal Signal（Signal 才是 workflow 间通信原语）。未来若引入总线命令（如第三方集成触发），作为新类型族显式设计，v1 不做。
- payload 必携 `entity_ref` 使消费者可回读业务库（幂等可重放的关键——事件负载不是数据真相）。

## 4. 版本策略（repo 即 registry）

- **registry 形态**：`schemas/events/<type>.schema.json`（实现期每事件类型独立文件；当前 v0.1.0 以 `schemas/message.schema.json` 单体承载 envelope + 首批类型）。git 历史即版本史。
- **兼容规则**：
  - additive（加 optional 字段 / 放宽约束）→ `version` 次版本 bump（`1.0` → `1.1`），新旧消费者共存；
  - breaking（改名/删字段/收紧约束/改语义）→ **发布新 type**（`order.paid` → `order.paid.v2`），旧 type 冻结不再生产；总线无 registry 时 **type 即隔离边界**——不搞共享队列内双 schema。
- **发布流程**：schema 文件 + 示例 + 校验测试（消费端按各自依赖的 version 校验）一并 PR；发布顺序 **先 producer 后 consumer**（producer 先发新 type、consumer 按 version 自控升级节奏）。
- **消费兼容**：消费者依赖明确 `type + version` 区间，升级节奏自控；毒消息（版本不匹配且不可忽略）按 §2 走 DLQ。

## 5. 关联追踪（三级 id 正交）

- `id`：消息唯一 UUID——幂等消费锚（消费端记已处理 id，配合业务键去重）。
- `correlation_id`：**业务链根**——一次端到端业务流的根实体 id（如 order_id：`order.paid → purchase.created → purchase.shipped` 同链）；同商品铺多渠道 = 多条独立业务流，各 Listing 铺货流的根是各自 listing_id，靠 payload 内商品引用（spu 引用）聚合回读，**不在消息层耦合**。
- `trace_id`：技术执行链（源头 workflow RunId 派生），跨消息传播不变，日志聚合用。
- 跨实体血缘（listing → order）走 payload `entity_ref` 逐跳 + DB provenance（#7 对象级血缘），不强行共用一个 correlation_id。

## 6. 错误与 DLQ

- **DLQ 范围**：只接投递/消费层失败（`CONSUMER_EXCEPTION` delivery 重试耗尽 / `DESERIALIZATION_FAILED` / `SCHEMA_VALIDATION_FAILED` / `DELIVERY_EXPIRED`）。业务失败在 workflow 层（RetryPolicy / Saga / reconcile，见 #11），不进 DLQ。
- **DeadLetterEnvelope**（队列里的 DLQ 消息 = **重放素材**）：`dead_letter { original_message(完整原 envelope), reason, error{class,message}, retry_count, dead_at, queue }`——payload 不丢，重放 = 解包原样重投。
- **信号事件**（广播 `sys.message.dead_lettered` = 告警/看板信号）在 DLQ 落库时发；**重放素材（队列）与信号（事件）两层分离**。
- DLQ 消费者 = 看板 + 人工/重放服务入口，绝无自动重投（ADR-0001）。

## 7. 首批事件类型（契约草案，见 message.schema.json）

| type | 域 | 触发 | payload 要点 |
|---|---|---|---|
| `order.paid` | 销售订单 | 买家已付款（落库后广播） | order_id, payment_amount, currency, paid_at |
| `listing.published` | 铺货 | PUBLISHED 终态 | listing_id, platform_item_id, published_at |
| `listing.ambiguous` | 铺货 | AMBIGUOUS 挂起（HITL） | listing_id, reason |
| `purchase.shipped` | 采购 | 供应商已发货 | purchase_order_id, shipped_at |
| `rma.closed` | 售后 | RMA 完结 | rma_id, order_id, outcome |
| `sys.workflow.failed` | 系统 | workflow failed 终态（告警） | workflow_type/id, run_id, error_type, reason |
| `sys.message.dead_lettered` | 系统 | DLQ 落库（信号） | queue, original_message_id, reason, dead_at |

## 8. 与既有契约的对齐

- envelope transport-agnostic = ADR-0001"总线可换、消息契约不破"的载体；type/version/payload 三要素齐备。
- 事件只在业务事实落库后产生（money 不作 first write：先落业务库、再发事件、消费端幂等可重放）。
- `entity_ref` 的 id 语义 = 各域确定性 id（listing_id/order_id/purchase_order_id/rma_id，对 workflowId 业务键），与 #7/#8 契约一致。
- 铺货/订单 workflow 终态是否广播 `listing.published`/`order.paid` 等由对应域实现决定；本规范只定事件契约形态，不强制每终态必发。
