# 消息 Schema：窄总线 + Domain Event 单层 + envelope 即契约（repo 即 registry、breaking 换 type）

消息契约定为：**总线 = 窄总线**——RabbitMQ 只承载**已落库业务事实的领域事件**（广播：一写多读，看板/通知/审计/未来 ERP），一切"驱动业务动作"的请求不经总线（workflow start/signal 或内部调用，对齐 #11 铺货入口不经总线、#13 有状态链进 workflow）；**推翻"raw→cleaned→event 三层物理 lane"假设**——平台 raw 负载落库（`platform_raw` JSONB / MediaAsset，#7/#8 已覆盖）、加工在 workflow 内部、落库即终点，总线只有 Domain Event 一层，分层体现在事件类型体系（order.* / listing.* / purchase.* / rma.* / sys.*）；**envelope = transport-agnostic 单信封**（`id/type/version/occurred_at/producer/entity_ref?/correlation_id?/trace_id?/payload`，与 CloudEvents 对齐但自研轻量不引 SDK）；**repo 即 registry + 兼容性双轨**（additive → version 次版本 bump 新旧共存；breaking → 发布新 type、旧 type 冻结——总线无内置 registry 时 type 即隔离边界）；**事件类型全事件化（过去时命名）无命令态**，跨 workflow 强信号走 Temporal Signal；关联追踪三级 id 正交（id 幂等锚 / correlation_id 业务链根 / trace_id 技术链）；**DLQ = 重放素材（DeadLetterEnvelope 原 envelope 完整包裹）+ 信号事件（sys.message.dead_lettered）两层**，只接投递/消费层失败，业务失败留在 workflow 层（RetryPolicy/Saga/reconcile），绝无自动重投（ADR-0001）。背景：envelope 的 transport-agnostic 细节由 ADR-0001 明示归本票；research（#4/#6）显示三方总线皆无开箱 schema registry、同类系统对消息信封的直接覆盖度低。细节见[规范 0004](../specs/0004-message-schema-versioning.md)，契约草案：[`schemas/message.schema.json`](../../schemas/message.schema.json)。

## Considered Options

- **宽总线：采集任务分发/webhook 信号/领域事件/DLQ 全走总线**（否决）：ticket 早期表述"采集层统一标准化 JSON 进中间件"的朴素读法，与已定执行架构冲突——有状态链进 Temporal（#13）、铺货入口不经总线（#11）、订单同步拉取为真相（#8）。总线消息无响应语义，驱动有状态链 = "至少一次投递 × 需幂等"的复杂度叠加；窄总线使执行架构与消息架构各司其职。
- **raw / cleaned / event 三层物理 lane**（否决）：平台 raw 与标准模型在总线里没有流经需求——raw 落库 `platform_raw`/MediaAsset，标准模型进 SPU/Order 表；加工在 workflow 内完成。三层 lane = 为不存在的流建管道，且每层 schema 独立演进会放大版本管理面。
- **引入 CloudEvents SDK 作为标准信封**（否决）：v1 无跨组织桥接诉求，SDK 是额外依赖与序列化约束；字段与其对齐（便于未来桥接）但不绑定实现。同理不引 schema registry 产品（Confluent 等）——repo + git 即 registry，v1 规模下零新增组件。
- **总线承载命令（command）+ 事件（event）双类**（否决）：命令语义（可重试/有响应）天然属于 workflow 层（Temporal start/signal/RetryPolicy），塞进总线是把编排语义降级成投递语义；type 体系保持全事件化（过去时），命令需求出现时显式再设计。
- **DLQ 单层：只留队列里死信消息、不广播信号**（否决）：死信消息是重放素材、不是通知——告警/看板需要事件流感知，故 DLQ 落库时发 `sys.message.dead_lettered` 信号事件；素材与信号职责分离，重放服务只读队列、看板只收事件。

## Consequences

- 总线演进（#15：单节点 → HA/Streams/RocketMQ）只换传输，envelope 契约不变；producer/consumer 不受总线形态影响。
- 事件负载轻量（引用 + 摘要），数据真相留在业务库——"money 不作 first write"与消费端幂等可重放（记已处理 id）成立。
- schema 演进有清晰护栏：additive 零成本共存、breaking 以 type 隔离；repo + git + PR 即发布流程，无 registry 运维。
- 事件命名即文档：`<domain>.<entity>.<past-tense>` 一眼可读；sys.* 与业务事件同信封，监控/告警消费同一条管道。
- 关联追踪三个正交 id 覆盖"消息唯一 / 业务链根 / 技术链"，跨实体血缘由 entity_ref + DB provenance 承担，消息层不背耦合债。
- DLQ 只接投递/消费层失败、业务失败留 workflow 层——失败语义归属清晰，不会把业务重试误投成死信。
