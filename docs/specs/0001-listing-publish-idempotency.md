# 规范：铺货发布幂等性与重试机制

> 来源：[Grilling: 铺货幂等性与重试机制](https://github.com/luochenfx/ecom-flowcart/issues/11)（Part of #1）
> 依赖：[ADR-0002（Temporal 工作流编排）](../adr/0002-temporal-for-workflow-orchestration.md) 的"失败分类 → Temporal 原语映射"在此细化；[ADR-0003（确定性 WorkflowId 幂等）](../adr/0003-listing-idempotency-via-deterministic-workflow-id.md) 记录决策与权衡。
> 状态：v1 设计期决议，供 #7（商品模型）/ #8（订单模型）落地时消费字段语义。

## 1. 决策概览

铺货（向目标平台发布/上架商品，外部 API 调用、必定失败）的幂等与重试机制：

- **幂等单元 = Listing**（`(product, channel)` 唯一组合）；一 Listing 一 Workflow Execution 一投影表行。
- **幂等载体 = 确定性 `WorkflowId`**（`listing-{productId}-{channelId}`，非 UUID）：登记动作 = workflow start 本身，无独立幂等登记表。
- **超时歧义 → reconcile 契约缺口 + 保守默认**：平台无"按外部引用查已存在商品"能力时，绝不自动重发 `add`。
- **终态编码**：`PUBLISHED` → workflow completed；`REJECTED` / `FAILED` → workflow failed（错误类型 + `reason` 区分）；`AMBIGUOUS` → 挂起等 signal（非终态）。
- **重铺/重放 = 复用同一 `WorkflowId` 的新 run**（`WorkflowIdReusePolicy = AllowDuplicateFailedOnly`），人工重铺与重放服务同一入口。
- **workflow 可重入**：开头先查 Listing 状态，已存在 `platform_item_id` 则直接完成，不重复 `add`。
- **分层**：铺货链入口不经 RabbitMQ；若事件触发，总线 redelivery 由消费端 execution 级幂等吸收。

## 2. 幂等单元与载体

### 2.1 Listing

**Listing = 把某商品发布到某渠道的一次铺货实例**，以 `(product_id, channel_id)` 唯一。`channel` = 平台店铺账号上下文（token、店铺维度）。同一商品铺到同一渠道的两个店铺 = 两个 Listing。

一 Listing ↔ 一 Workflow Execution ↔ 一 `execution_projection` 行（`type='listing'`）。

### 2.2 确定性 WorkflowId

```
workflowId = "listing-" + productId + "-" + channelId
```

- 非 UUID——任何重复铺货指令都派生同一 workflowId，天然命中同一 execution。
- 首次调用 `startWorkflow` 创建 execution（= 意图登记，原子，无需前置登记表）。
- 重复调用触发 Temporal `AlreadyStarted` → 捕获后视为"重复提交"，返回既有 execution（等价于 FuSign `clientNoteId` 语义中的"重复提交返回既有记录"）。

### 2.3 不建独立幂等登记表

平台 API 不接受自定义幂等键、也无"按外部引用查重"端点（research #2/#3 萃取结论，三目标平台均未明确覆盖）→ clientNoteId + unique constraint 模式无法原样移植到平台侧。幂等由 Temporal 执行级唯一性承担，不再自建需要自己保证正确性的"幂等表模拟唯一约束"。

## 3. Listing 状态机

Event History 为执行真相；投影表 `status` 列承载看板/列表（供人工与告警消费）。

```
                     ┌─────────────────────────────────────────┐
                     │          ListingWorkflow (start)         │
                     │  可重入检查：projection 已有 platform_item_id? │
                     │  ├─ 有 → 直接 COMPLETE（PUBLISHED 幂等命中）   │
                     │  └─ 无 → reconcile(optional) → 有结果则回填    │
                     │          └─ 无果 → add Activity            │
                     └─────────────────────────────────────────┘
                                     │
                     ┌───────────────┼───────────────┐
                     ▼               ▼               ▼
              add 成功            超时/连接断开      限流/5xx 重试耗尽
              (platform_item_id)  (Ambiguous)       (Retryable 耗尽)
                     │               │               │
                     ▼               ▼               ▼
                 PUBLISHED       AMBIGUOUS        FAILED (workflow failed)
               workflow         workflow 挂起      可重放（同 workflowId 新 run）
               completed        等 signal：
                               └ 人工确认回填 → 完成
                               └ 确认未生效 → 重试 add
                               └ 业务拒绝 → REJECTED
```

| 状态 | workflow 收尾 | 含义 | 后续 |
|---|---|---|---|
| `PUBLISHED` | completed | add 成功，已回填 `platform_item_id` | 终态；误触重复提交被 `AlreadyStarted` 拒绝 |
| `AMBIGUOUS` | 挂起（非终态） | 超时/断连，不知平台是否已生效 | 等 signal；重复 start 仍 `AlreadyStarted` |
| `REJECTED` | failed | 业务拒绝（`NonRetryable` + Saga 后收尾） | 人工修复后可重铺（同 workflowId 新 run） |
| `FAILED` | failed | 可重试类错误重试耗尽 / 意外未知 | 重放服务或人工重铺（同 workflowId 新 run） |

> 区分 `REJECTED` 与 `FAILED` 靠**错误类型 + 结构化 `reason`**（投影表 `status` 列 + `terminal_reason`），不靠 workflow 收尾方式——二者都以 failed 收尾以启用 `AllowDuplicateFailedOnly` 复用。

## 4. 终态后重铺 / 重放语义

- **`WorkflowIdReusePolicy = AllowDuplicateFailedOnly`**：
  - 命中 running / completed → `AlreadyStarted` → 幂等返回既有（防 `PUBLISHED` 后重复铺货）；
  - 命中 failed（`REJECTED` / `FAILED`）→ 复用同一 `WorkflowId` 开新 run。
- **重铺与重放同一入口**：人工"修复后重铺"与 #13 的重放服务都只是对同一 `WorkflowId` 再次 `startWorkflow`，无需区分"新意图 vs 重放"。
- **workflow 可重入性**（防重放重复 add）：新 run 开头先查 Listing 状态——已有 `platform_item_id` → 直接 completed；否则 add Activity 先走 `reconcile`（见 §6），有平台结果则回填，无果才真正 `add`。这把"先查后写"落实到 execution 开头，对账重心从"平台侧查重"（做不到）移到"我方记录先行 + workflow 重入检查"。

## 5. Adapter 错误分类契约（Adapter SPI）

Adapter 调用平台时抛错分三类（契约级，与具体平台参数解耦）：

| 异常类型 | 语义 | 映射 | 示例 |
|---|---|---|---|
| `RetryableException` | 临时故障，可重试 | Activity RetryPolicy（指数退避） | 限流、5xx、网络抖动 |
| `NonRetryableException` | 业务拒绝，重试无意义 | `NonRetryableErrorTypes` + Saga 补偿 | 资质缺失、类目违规、参数非法 |
| `AmbiguousException` | 超时/断连，不知是否生效 | 不自动重发 → reconcile/人工路径 | add 请求超时 |

- 错误须携带结构化 `reason`（平台错误码 + 可读中文描述），落 `last_error` / `terminal_reason`。
- Adapter SPI 暴露**可选的** `reconcile(externalRef) -> platformItemId?` 能力：支持者用于先查后发（平台侧确有商品则回填，不重复 add）；未实现或查询无果时走保守路径（不自动重发）。
- 平台侧 reconcile 可实现度（淘宝 `outer_id`/`outer_sku_id`、速卖通 message subscription、拼多多商品侧查询）为 fog，随平台 Adapter 票推进时核实，不在本票假设现成答案。

## 6. 重试参数面（起步默认）

平台级调优 out of scope（见 #11 body）；仅定机制与起步默认，随真实 Adapter 校准：

- Activity RetryPolicy 起步默认：`MaximumAttempts ≈ 5`、指数退避 `1s × 2`（可含 jitter）、`ScheduleToCloseTimeout` 兜底。
- 跨平台限流差异大（速卖通 QPS≈5、淘宝接口级 1000/200 rpm、1688 类目 QPS≤10）→ 限流表现为 `RetryableException`，具体退避参数由各 Adapter 按平台实际配置。

## 7. 消息 redelivery 与 API 重试分层

- **铺货链 = 有状态链，入口不经过 RabbitMQ**：触发源（人工 / 商品就绪 / 调度）直接调 `startWorkflow`（确定性 workflowId）。RabbitMQ 只承载无状态事件。
- 若未来出现"事件触发铺货"，总线 redelivery（至少一次）由消费端 execution 级幂等吸收：同一事件重投 N 次 → 命中同一 execution → 不产生重复 Listing。
- **分层表述**：消息层保证至少一次投递；消费端以确定性 `WorkflowId` 幂等吸收重复 → 对 Listing 达成端到端恰好一次效果。总线只管投递，API 重试/幂等全在 workflow 层（对齐 ADR-0001：DLQ 不自动重投、恢复归工作流层）。

## 8. 投影表扩展（字段语义，供 #7 落库）

ADR-0002 投影表 `type='listing'` 行在本票细化的字段：

```
execution_projection (type='listing' 行)
├── biz_key            # Listing canonical id（幂等锚点，= workflowId 语义对应物）
├── platform_item_id   # add 成功回填的平台商品 ID（订单回传关联键，可空=未发布）
├── platform_item_url  # 平台商品链接（可空）
├── published_at       # PUBLISHED 时间（可空）
├── terminal_reason    # REJECTED / FAILED 的结构化原因（平台错误码 + 中文描述）
└── (其余列沿用 ADR-0002：execution_id / status / current_step / retry_count / last_error / ...)
```

- 订单回传模块以 `platform_item_id` 关联订单快照。
- `execution_projection` 保持"薄投影"定位：无状态机逻辑，仅由 workflow 在关键节点写入的事实字段。
