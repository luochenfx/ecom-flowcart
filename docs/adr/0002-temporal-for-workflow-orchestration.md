# Temporal（自托管）作为工作流编排引擎

工作流编排引擎选型定为 **Temporal，自托管部署**（参考部署：单机 8C16G + PostgreSQL 后端，独立 database、可与业务库同实例）。背景：一件代发电商自动化（设计先行、将开源），用户已定"代码定义 workflow 是骨架"（可视化拖拽后置）；业务链为 采集 → 清洗/AI Step → 铺货 → 订单回传，铺货是外部调用必失败、订单生命周期跨天等待外部异步事件。选 Temporal 的理由：code-first 与既定方向一致；跨步重试 / durable timer / signal / Saga 补偿 / Worker Versioning（在途执行跨版本升级）内建，成熟 Java SDK + Spring Boot 集成；每商品/每订单映射为一个 Workflow Execution（免自建状态表，Event History 天然审计轨迹）。8C16G 重校准后"自托管运维面不现实"的否决理由失效（原 research 以 2C2G 为前提）。

## Considered Options

- **自研轻量编排（执行实例表 + 状态机 + RabbitMQ 驱动）**（否决）：订单天级链需要自建 durable timer、状态机转移守卫、事件接入、在途记录跨版本升级——是本项目（Contributor 写 Adapter/AI Step 而非基础设施）最不该自造的车轮；崩溃恢复与幂等需自行保证，无框架级重放。
- **Temporal Cloud**（否决为 v1 默认，保留为演进选项）：Essentials 起 $100/mo，OSS 自托管用户扛不住成本；自托管遇运维瓶颈时迁移（SDK 不变，成本低）。
- **Restate / DBOS（轻量 durable execution）**（否决）：更轻运维但 Java 成熟度与社区规模 < Temporal，为开源项目新增陌生依赖，收益不抵风险。
- **Camunda Zeebe / Flowable**（否决）：BPMN 视觉建模优先，与"代码定义 workflow 是骨架"方向直接冲突。
- **Spring Statemachine**（否决）：定位为状态建模工具，非编排引擎，无 durable execution / 跨步重试。

## 分工边界（编排层 vs 消息总线）

- **有状态 → Workflow（Temporal）**：需要"记住进行到哪"的链——铺货链、订单生命周期；每商品/每订单一个 Workflow Execution。
- **无状态 → RabbitMQ**：事件通知、扇出、削峰、纯异步分发。
- **money 事件 DB-first-write 铁律不变**（先落业务库再发事件，消费端幂等可重放，对齐 ADR-0001）。

## 执行实例读模型（看板/列表查询）

- **Temporal = 执行真相**：Event History（审计/调试）+ Query（单实例状态）。
- **薄投影表 = 看板/列表查询源**：Workflow 在关键节点（每步 Activity 完成/失败时）写投影表；表不含状态机逻辑（避免双写）；Basic Visibility 不足以支撑看板过滤，且不引入 Elasticsearch（Advanced Visibility 依赖，运维面与自托管门槛代价不值）。

投影表草案（字段语义随 #7 商品模型 / #8 订单模型 / #11 幂等细化）：

```
execution_projection
├── biz_key          # 业务键（商品/订单的 canonical id，幂等锚点）
├── execution_id     # Temporal WorkflowId
├── type             # pipeline 类型（listing / order-lifecycle …）
├── status           # 阶段状态（见下游状态机票）
├── current_step     # 当前步骤（清洗 / ai / listing / …）
├── retry_count
├── last_error       # 最近一次失败摘要（供告警/重放服务消费）
├── last_heartbeat_at
├── created_at / updated_at
```

## 失败分类 → Temporal 原语映射（供"铺货幂等性与重试"票细化）

- **业务预期内失败**（限流、临时 5xx 等可重试）→ Activity RetryPolicy（MaximumAttempts / ScheduleToCloseTimeout + 指数退避），对齐 ADR-0001 决策 3。
- **业务拒绝 / 不可恢复** → NonRetryableErrorTypes + Saga 补偿。
- **意外未知失败** → workflow 失败分支 → 告警 / 独立重放服务（或人工），DLQ 只作隔离舱、绝不自愈（ADR-0001）。

## Consequences

- 引入常驻 Temporal Server（frontend/history/matching/worker）+ 独立 database 的运维面与监控；Worker（运行 workflow/activity 代码的进程）由本应用承担。
- Workflow 代码须遵守确定性约束（不得直接调用 `Date.now()`/随机数/网络——放 Activity）；部署升级用 Worker Versioning（Build ID pinned）避免旧执行被新代码错误重放。
- 看板不依赖 Temporal Visibility（Basic 不够、Advanced 要 ES），统一走投影表。
