# 架构骨架与模块边界：模块化单体 + 单一依赖规则 core ← 一切 + 单仓契约治理 + REST 主入口（MCP 预留）+ compose 单机部署

总装票（#14）把已落定子决策收敛为高层架构：**运行时拓扑 = 模块化单体应用进程 + 三个基础设施容器**（Postgres / RabbitMQ / Temporal Server）——应用 = 1 个 Spring Boot artifact、启动角色可切（`api` / `worker` / `scheduler`），内含模块 `catalog`（采集/SPU/SKU/MediaAsset）/ `content`（AI Step 内容链）/ `publish`（铺货）/ `order`（同步/采购/RMA）/ `projection`+`api`（读侧/看板/人工 reconcile 入口）/ `adapter-host`（SPI 装配）/ `worker-runtime`；**AI 模型后端 = 外部 HTTP 端点**（OpenAI/本地 Ollama/vLLM，对齐 #12 LLMProvider HTTP 直连），不是内部"AI 微服务"。Context 中"Redis Streams 初期""采集层标准化 JSON 进中间件""处理层=独立服务""初期 2C2G"均为早于子决策的过时表述，分别被 #15（ADR-0001 RabbitMQ）/ #10（ADR-0006 落库+事件进总线）/ 本票 / 基线修正（最低 4C8G、推荐 8C16G，research 无原始数字、以修正决议为准）取代。**依赖方向 = 单一规则 `core` ← 一切模块，模块间不依赖实现只经 SPI**——core = 标准模型 POJO + JSON Schema + 契约接口（Capability / AI Step / envelope type）+ 领域术语，零 Spring/平台依赖；三个编译期禁环（ArchUnit 一张测试类自动拦，社区 PR 过不了 CI）：① adapter/AI Step 实现 → 业务模块；② 业务模块 → 具体平台/模型类（只认 core 接口）；③ 读侧/投影 → 写侧内部实现。**契约仓库形态 = 单仓 Maven 多模块、契约仓内治理**——`schemas/*.json`（数据/消息契约）+ `core-contracts` 模块（Java 契约接口/领域模型，零 Spring/平台依赖）+ `docs/`（规范）三形态同仓同 commit（变更原子）；无独立 registry/SDK 发布（对齐 #10 repo 即 registry + UCP 先例）、不引 codegen（schema 与 Java 一致性靠双向 fixture 契约测试，#9 门槛）。**API 入口 = v1 无 Gateway，对外单一 REST `/api/v1/*`；MCP 后置预留 seam 首版不做**——Gateway 是微服务边缘组件，单体进程内调用多一跳无收益（否决）；MCP 消费者是 AI agent 而非人工，v1 用户走看板 UI，且铺货触发含 HITL 安全语义（#11），预留 = core 服务接口即现成边界，未来加 transport 模块实现 MCP 协议即暴露（UCP 式可插拔 transport），届时先只读（查询）后写。**部署视图 = 单机 docker-compose 起步**：`postgres`（单一实例两 database：`flowcart` 业务库 + `temporal` 库，3–4G）/ `rabbitmq`（Quorum，#15，0.5–1G）/ `temporal`（自托管 Server 4 角色 + admin-tools 一次性引导容器，2–3G）/ `app`（合一 role，4–6G）/ `temporal-ui`（可选 0.5G）；**拆分演进信号**：单 worker CPU 饱和或 API 延迟被 AI Step 长调用拖累 → 拆 `app-worker` 独立容器（同 artifact `--role=worker`）；多机器 → 才引入服务发现/Gateway（届时再议，v1 不设计）。备份职责 = postgres 双库 pg_dump 定时（业务库 + temporal 库），RabbitMQ/Temporal 无状态可重建。背景：设计期 6 张 blocker 票（#7/#8/#9/#10/#11/#12）全清后本票为收官总装——各子决策已把"层"压扁（无 raw/cleaned 消息流、无独立服务语义），架构须按已定形态收敛而非按 Context 旧图拼装。细节见[架构总览](../architecture.md)与各子决策 ADR。

## Considered Options

- **微服务化（每层独立部署服务，Saleor MACH 式）**（否决）：单机 8C16G 无弹性收益、运维/编排成本翻倍；Temporal worker + RabbitMQ consumer 本就是进程内 worker 语义，拆成网络服务重复造"服务间调用 + 服务发现 + 重试"三件套；开源社区项目无人力养多服务。模块化单体 = 同一套乐高模块，拆分信号出现前不付网络代价。
- **层间直接依赖（业务模块 import 具体平台/模型类）**（否决）：#7 平台污染 master 的代码级镜像；Adapter/AI Step 反向依赖业务模块 = 插件绑架宿主，升级即爆破。单一 `core ← 一切` + 只经 SPI = 依赖方向可被 ArchUnit 机械校验。
- **契约独立 registry / SDK 单独发布 + schema codegen**（否决）：v1 无对外 SDK 消费者（开源即仓库本身），registry 是运维债；codegen 让 schema 与 Java 双源需同步，UCP 先例为手工维护 + 契约测试保证一致——同仓同 commit 已保变更原子。
- **Spring Cloud Gateway / 服务网格作 API 入口**（否决）：Gateway 的服务发现/路由/聚合价值在微服务拓扑才显现，进程内调用加网关 = 纯多一跳；单体对外一个 REST 端点即可，v1 不预铺边缘层。
- **MCP 首版即开放（含写操作）**（否决）：v1 消费者是人工（看板 UI），无 AI agent 调用方；铺货触发含 HITL 安全语义（#11 AMBIGUOUS 等人工 reconcile），agent 直连写操作需授权/审计语义成熟——先只读后写，transport seam 预留即零成本升级路径。
- **compose 预铺多容器多角色（api/worker/scheduler 各自独立容器）**（否决）：单机起步多容器只是把进程拆开看，无隔离收益且加编排负担；合一 role + `--role` 切换 = 拆分是配置不是重构。
- **部署基线沿用 ticket Context 的 2C2G**（否决）：与 2026-09-08 基线修正冲突（最低 4C8G / 推荐 8C16G）；Temporal 生产自托管 + RabbitMQ + Postgres 同机 2C2G 不可行（research：2C2G 下生产级 Temporal 不现实、轻量形态官方非生产）。

## Consequences

- 新贡献者心智负担低：clone 单仓 → 读架构总览 → 认领一个模块；模块边界 = Maven 模块 + SPI 契约，无需理解服务网格/网关/注册中心。
- 依赖方向机械可守：ArchUnit 禁环测试拦社区 PR，core 纯净性（零 Spring/平台依赖）不靠 code review 自觉。
- 契变更原子：schema + core-contracts + 文档同 commit，双向 fixture 测试是唯一一致性门槛，无 codegen 双源漂移。
- 拆分是配置：性能瓶颈出现时同 artifact 换 role 即得独立 worker 容器；多机才引入服务发现——演进信号明确，不为未来付现在。
- 部署可复现：compose 清单 + 资源预算 + 双库备份职责成文，单机 8C16G 即可跑完整双链路。
- 部署形态修订（2026-09-09，随 #18 落地）：temporal 弃用官方 deprecated 的 `auto-setup` 镜像 → `temporalio/server` + `temporalio/admin-tools` 一次性引导（建 schema / 注册 default namespace），镜像 tag 随官方版本线独立升级；细节与启动时序见 ADR-0002「部署形态修订」与 docker-compose.yml。
