# RabbitMQ（Quorum Queues）作为 v1 消息总线

v1 消息总线选型定为 **RabbitMQ，队列形态默认 Quorum Queues**。背景：一件代发电商自动化系统（设计先行、将开源）需要一条解耦 采集 → 清洗/AI Step → 铺货 → 订单回传 的传输总线；约束为 Java 21 / Spring Boot 生态、自托管单机起步（最低 4C8G、推荐 8C16G，2026-09-08 修正——原误带 FuSign 的 2C2G 遗产已剔除）、轻运维、绿地部署（无既有 Redis/Rabbit 遗产）、Kafka 因运维成本被否决。选 RabbitMQ 的理由：绿地部署下 "Redis Streams 零新增组件" 的前提不成立（无既有 Redis 可复用），Redis Streams 需自研 DLQ/janitor glue 并承担 AOF 丢失窗口；RabbitMQ 原生提供 DLX + delivery-count 重试（默认 20 次）+ Quorum 每写 fsync 落盘，Spring AMQP 4.x 是三者中最成熟的集成，且对开源自托管者认知度最高。Schema 治理三方皆无开箱能力，由 transport-agnostic envelope（type/version/payload）承担（细节归"消息 Schema 分层"票），总线可换、消息契约不破。

## Considered Options

- **Redis Streams**（否决）：绿地无既有 Redis 可复用、"零新增"不成立；需应用层自研 DLQ（`XPENDING` + 超次 `XADD` 到 `*:dlq`）与 janitor；AOF `everysec` 存在最多 1s 丢失窗口。
- **RocketMQ**（否决）：NameServer + Broker 双进程 JVM 运维最重；rocketmq-spring 对 Spring Boot 3.x/Java 21 的版本矩阵脆；自带 Schema Registry 子项目仍在孵化、需另部署。
- **Redis 原型 + RabbitMQ 文档默认 双后端**（否决）：v1 扛双集成面是纯负债。
- **Kafka**（既有否决，不开）：运维成本；除非出现强理由 reopen。

## 单节点形态与演进信号

v1 接受**单节点 Quorum 单副本**起步（Raft 多数派语义在单节点不成立，由"DB 为 record + 消费端幂等可重放"兜底）。演进信号（触发换形态/升级，迁移机制 = envelope 契约不变 + 消费端幂等 → 双跑 → 切流 → 排空）：

1. **需要 HA** → 扩 3 节点 Quorum 集群；
2. **单 lane 积压持续 >5M 或需长时间随机 replay** → 该 lane 队列形态从 Quorum 换 RabbitMQ Streams；
3. **出现强分区有序 / 吞吐需求** → 才评估 RocketMQ（需强理由）。

## 失败边界（约束下游 幂等重试 / 工作流编排 票）

- **RabbitMQ DLQ = 隔离舱 + 信号源，绝不做自动恢复（不自动重投）**；delivery-limit 耗尽 → 进 `*.dlq` + 告警。
- 失败分类（"业务预期内 vs 意外未知"的判别范围由下游票定义）：
  - **业务预期内失败** → 工作流层（Temporal 方向，编排技术选型归"工作流编排"票）处理；
  - **意外未知失败** → 独立的重放服务（或人工）处理。
- **money 事件总线永不作 first write**：先落业务库、再发事件、消费端幂等可重放（订单快照/铺货结果等关键数据不依赖总线持久性）。
