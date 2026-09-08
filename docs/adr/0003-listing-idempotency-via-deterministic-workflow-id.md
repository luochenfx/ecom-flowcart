# 铺货发布幂等：确定性 WorkflowId + execution 级幂等（不建幂等登记表）

铺货（外部 API 调用、必定失败）的幂等载体定为 **Temporal 确定性 `WorkflowId` + execution 级唯一性**，而非自建幂等登记表。背景：三个目标平台（淘宝/拼多多/速卖通）的发布 API 均为同步 HTTP、不接受自定义幂等键，且无"按外部引用查已存在商品"的查询端点（research #2/#3 萃取）→ FuSign 的 `clientNoteId` + unique constraint 模式无法原样移植到平台侧。因此幂等由"一 Listing（`(product, channel)`）一 Workflow Execution、`workflowId = listing-{productId}-{channelId}` 确定性派生"承担：登记动作 = workflow start 本身（原子、无需前置登记表），重复指令命中 `AlreadyStarted` 即视为重复提交并返回既有 execution。细节与状态机见[规范 0001](../specs/0001-listing-publish-idempotency.md)。

## Considered Options

- **自建幂等登记表（clientNoteId + unique constraint 直移）**（否决）：平台不提供自定义幂等键与"按外部引用查重"端点 → 唯一约束只能落在己方表，等于用应用代码重新实现 Temporal 已保证的 execution 唯一性，多一个需要自己保证正确性的组件。
- **请求级幂等键（每次 API 调用发 UUID + 平台去重）**（否决）：依赖平台侧幂等能力，事实不存在。
- **重试策略不加区分（一律 RetryPolicy 重试到死）**（否决）：业务拒绝（资质/类目/参数）被无限重试是纯浪费，必须以 `NonRetryableErrorTypes` 短路。

## Consequences

- 终态编码：`PUBLISHED` → workflow **completed**；`REJECTED` 与 `FAILED` 均以 workflow **failed** 收尾（靠错误类型 + `reason` 区分），使 `WorkflowIdReusePolicy = AllowDuplicateFailedOnly` 能同时满足"PUBLISHED 后误触拒绝"与"REJECTED/FAILED 后重铺放行"。
- 重铺/重放共用同一入口（同 workflowId 新 run）；workflow 须**可重入**（开头先查 Listing 状态，已有 `platform_item_id` 直接完成，防重放重复 add）。
- Adapter SPI 错误分三类（`Retryable` / `NonRetryable` / `Ambiguous`）；`AmbiguousException`（超时不知是否生效）绝不自动重发，走 reconcile/人工。
- Adapter SPI 预留**可选的** `reconcile(externalRef)` 缺口；未实现时保守默认（不自动重发）。
