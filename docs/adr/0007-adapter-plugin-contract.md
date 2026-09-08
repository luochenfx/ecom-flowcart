# 平台 Adapter 插件化契约：capability 接口族 + core 零平台依赖 + SPI（错误/凭据契约收口）

平台接入契约定为：**每平台一个 Adapter 插件化模块**，core 定义 **Capability 接口族（可部分实现）**——`Publish` / `OrderSync` / `Address` / `Shipment` / `Rma` / `OfferFetch` / `Purchase` / `Auth`，workflow activity 依赖能力接口而非平台类（可部分实现 = 贡献者可只维护订单部分，未实现不影响 core 其它链路；RMA 操作类 v1 只读、动作留平台后台人工）；**转换三分边界**——结构转换（平台字段 ↔ 标准模型、截断、枚举翻译）写 Adapter 代码（平台知识、类型安全可单测），业务映射（类目/规格/定价）已是 #7 的 **Listing 数据**（Adapter 只消费不内置，可被 AI/人工/规则产出修改），逃生口 = `platform_raw` JSONB 直通（不造映射 DSL，贡献者无需学一套映射语言）；**插件机制 = core 零平台依赖 + Adapter 独立 Maven 模块（自带平台 SDK）+ Java SPI（ServiceLoader）注册 + 无中央注册表**（core 构建/发布不受平台 SDK 影响；契约 breaking = core 大版本 + 双跑期，平台 API 升级 = 模块内适配不破接口）；**认证收敛 = Channel 域**——凭据 AES 加密落库（`credentials {type, encrypted_payload, status, expires_at}`）、接口传 CredentialView 不传明文 token（防日志泄漏）、长期 token 人工刷新 + EXPIRED 告警、OAuth refresh 平台走自身 Adapter 的 AuthCapability（平台协议在平台模块，core 只消费"凭据可用性"）；**错误契约 = `AdapterException{kind: RETRYABLE|NON_RETRYABLE|AMBIGUOUS, platform_code?, message, retryable_after?}`**（#11 三类落成接口契约，workflow RetryPolicy/Saga/reconcile 直接消费；所有能力接口只抛它、不裸抛平台 SDK 异常；**非 AdapterException 的异常 = bug 而非平台错误**，按 NON_RETRYABLE 收口防误重试）；**限流自治在 Adapter 内**（每平台 token bucket：速卖通 QPS≈5、淘宝 1000/200rpm、1688 类目 ≤10——平台节奏知识自治、超限本地排队，core 不配平台限流参数）；测试与社区验收门槛 = **双向 fixture + core 契约校验 + 三类错误映射测试（硬门槛）**，VCR 可选，PR checklist 四查（fixture 全过 / 错误测试 / 认证 README / 不破坏 core 构建）。背景：Adapter 是 #7/#8/#11/#10 的收口点，全部平台交互（铺货/订单/采购/售后）在此交汇；research（landscape #6/#4）提供 OpenPIM ChannelHandler / UCP capability 哲学 / Truto 逃生口可借鉴，但认证收敛、统一错误码、无账号测试三大块未覆盖（以已定决策 + 业界范式补齐）。细节见[规范 0005](../specs/0005-adapter-plugin-contract.md)。

## Considered Options

- **巨型单接口（`PlatformAdapter { publish; fetchOrders; ship; ... }`）**（否决）：平台横跨多域（1688 采集+采购、淘宝/拼多多/速卖通铺货+订单+物流+售后），单接口强迫"只想维护订单部分的贡献者"实现全部；能力接口族让平台与贡献者各取所需，未实现不影响 core。
- **全声明式映射 DSL / JSONata 承载结构转换**（否决）：平台字段映射每处有真实语义（淘宝 `outer_sku_id` ↔ 我方 sku_id、速卖通 `sku_code` ≤20 字符截断），抽 DSL 是把简单事情复杂化且类型不安全；业务映射（确实该共享的"知识"）#7 已落成 **Listing 数据**，不存在"锁死在代码里的业务映射"需要 DSL 化——结构进代码、映射进数据、逃生口 JSONB，三分各得其所。
- **Adapter 内嵌 Spring 容器/插件框架自动装配**（否决）：平台模块耦合 Spring 会阻碍独立演进、测试与社区 fork；Java SPI 让 Adapter 零框架依赖，Spring 生态由 core 薄加载器桥接（`@ConditionalOnClass` 装配），两种消费方式都成立。
- **中央注册表 / core 维护"平台清单"**（否决，UCP 哲学）：平台集合随社区增长不可枚举；core 只认能力接口 + 模块在 classpath 即被发现，清单属运维视角不属契约。
- **凭据明文直传 / 认证散落在各 Adapter 调用里**（否决）：token 泄漏风险（日志）与刷新职责失控；凭据收敛到 Channel 域（AES 落库 + CredentialView + ensureValid 统一触发），平台刷新协议仍留在平台模块（AuthCapability），安全与灵活兼得。
- **裸抛平台 SDK 异常 / core 逐平台配错误与限流参数**（否决）：平台 SDK 异常互不兼容，workflow 无法统一决策 Retry/不重试/reconcile；限流参数是平台节奏知识，core 不背——AdapterException 契约 + Adapter 内 token bucket 使失败语义归属清晰。
- **质量靠真实账号集成测试**（否决）：速卖通仅企业可接入、个人开发者无法实测，把门槛建立在多数贡献者拿不到的账号上 = 事实性关门；双向 fixture + 契约校验器（JSON Schema）+ 模拟平台把验证前置到无账号即可执行，VCR 仅作进阶。

## Consequences

- 社区贡献者维护自己平台的 Adapter 模块：只依赖平台 SDK + 标准模型 + 能力接口，无需理解 Temporal/RabbitMQ/Spring 内部；业务映射以数据形态（Listing）可被 AI Step/人工/规则共同产出——平台"知识"与经营"决策"彻底分家。
- core 构建/发布与任何平台 SDK 解耦（编译失败不影响 core）；平台 API 升级的爆炸半径锁在单模块内。
- workflow 层失败决策零翻译：RetryPolicy ← `RETRYABLE`、Saga ← `NON_RETRYABLE`、reconcile ← `AMBIGUOUS`，限流由 Adapter 自排队吸收，core 无平台参数。
- 无真实账号的测试基线成立：双向 fixture 是"贡献者承诺的映射语义"，错误映射测试验证三类翻译正确性——PR 门槛不依赖平台账号可及性。
- 认证安全基线：明文 token 不进接口不进日志；凭据状态（EXPIRED）驱动人工刷新看板；OAuth 平台的自刷新不阻塞人工 token 平台。
