# 规范：平台 Adapter 插件化契约

> 来源：[Grilling: 平台 Adapter 插件化契约](https://github.com/luochenfx/ecom-flowcart/issues/9)（Part of #1）
> 依赖：[ADR-0004/#7（商品模型）](../adr/0004-product-catalog-model.md)（Listing 是铺货唯一输入）、[ADR-0005/#8（订单模型）](./0003-order-model.md)（单写入路径/游标/地址解密/RMA）、[ADR-0003/#11（幂等）](./0001-listing-publish-idempotency.md)（错误三类 + reconcile）、[ADR-0006/#10（消息）](../adr/0006-message-schema-and-versioning.md)（事件由 workflow 层发）。
> 状态：v1 设计期决议。契约的机器可读形态是 **Java 能力接口**（代码级，非数据/消息 payload），接口签名随本规范落盘，实现期在 core 模块落成 `contract` 包；`schemas/` 目录保留给数据/消息契约，不为 Adapter 造伪 schema。

## 1. 决策概览

- **Adapter = 每平台一个插件化模块**：负责「标准模型 ↔ 平台模型」双向转换 + 平台认证/签名 + 平台特有校验/限流。core 定义契约（能力接口 + 数据模型），**不 import 任何平台 SDK**。
- **Capability interface 模式（可部分实现）**：core 定义一组能力接口，平台 Adapter 按需实现，未实现不影响 core——贡献者可以只维护订单部分。
- **转换三分边界**：结构转换（平台字段 ↔ 标准模型、截断、枚举翻译）= **Adapter 代码**；业务映射（类目/规格/定价）= **Listing 数据**（#7 已落成数据，Adapter 只消费不内置）；逃生口 = **`platform_raw` JSONB 直通**，不造映射 DSL。
- **插件机制 = core 零平台依赖 + Adapter 独立 Maven 模块 + Java SPI（ServiceLoader）**，无中央注册表（UCP 哲学：core 只认能力接口 + classpath 存在即被发现）。
- **认证收敛 = Channel 域**：凭据 AES 加密落库（`credentials {type, encrypted_payload, status, expires_at}`），接口传 **CredentialView**（解密内存对象）不传明文 token；长期 token 人工刷新（`EXPIRED` 告警），OAuth refresh 平台走 Adapter 的 `AuthCapability`。
- **错误契约 = `AdapterException{kind, platform_code?, message, retryable_after?}`**（#11 三类落成接口契约）；**限流自治在 Adapter 内**（每平台 token bucket，超限本地排队不抛 429）；**非 `AdapterException` 的异常即 bug**（NON_RETRYABLE 收口防误重试）。
- **测试门槛 = 双向 fixture + core 契约校验 + 三类错误映射测试**（硬门槛）；VCR 真实录制回放可选非门槛；PR checklist 四查。

## 2. Capability interface（能力粒度）

core 定义能力接口族，各平台 Adapter 选择性实现。签名草图（实现期落 `contract` 包，此处语义先行）：

```
interface PublishCapability      // 铺货（#11 消费方）
  PublishResult add(ListingView listing)            // → platform_item_id/url；抛 AdapterException
  Optional<PlatformItemRef> reconcile(String externalRef)  // 可选：#11 缺口，超时歧义时核实生效与否

interface OrderSyncCapability    // 订单同步（#8 单写入路径：拉取为真相）
  OrderPage fetchOrders(SyncCursor cursor)           // 增量拉取（按平台时间/ID 游标）
  OrderDetail fetchOrderDetail(String platformOrderNo)

interface AddressCapability      // 地址解密（#8 MASKED→DECRYPTED 延后取明文）
  DecryptedAddress decryptAddress(AddressRef ref)    // 下采购单前触发

interface ShipmentCapability     // 发货回传（供应商物流单号 → 销售平台）
  void notifyShipment(ShipmentNotification n)

interface RmaCapability          // 售后（v1 查询只读；操作留平台后台人工）
  RmaStatus fetchRmaStatus(String platformRmaId)

// —— 1688 货源/采购侧（同一套模式）——
interface OfferFetchCapability    // 采集：拉取 offer/SKU/类目属性
  OfferData fetchOffer(SourceRef ref)
interface PurchaseCapability      // 采购：fastCreateOrder/取消/支付/物流追踪
  PurchaseResult createPurchase(PurchaseDraft draft)
  LogisticsTrace fetchLogistics(String platformPurchaseNo)

interface AuthCapability          // 可选：OAuth refresh 等平台专属凭据刷新
  Credential refresh(Credential stale)
```

- 未实现的能力 = core 侧该链路不可用（如纯采购平台 Adapter 不实现 PublishCapability），启动期能力探测（SPI provider 声明实现清单），**不影响 core 其它链路**。
- RMA 操作类（同意退款/申诉动作）v1 不做：无经营背景约束下动作收敛到平台后台人工，Adapter 只读状态供看板/告警（OrderRMA 状态机由人工在平台侧完成后经轮询同步）。

## 3. 转换三分边界

| 层 | 内容 | 归属 | 理由 |
|---|---|---|---|
| **结构转换** | 平台字段 ↔ 标准模型映射、类型转换、截断规则（如速卖通 `sku_code` ≤20 字符）、枚举翻译 | **Adapter 代码** | 平台知识，类型安全、可单测；正是社区贡献者"自己熟悉的平台"部分 |
| **业务映射** | 来源类目 → 目标叶子类目、canonical 规格 → 平台属性 ID、SKU 定价策略 | **Listing 数据**（#7：`platform_category`/`spec_mappings`/`platform_attributes`/`sku_set`） | 已由 #7 落成数据，Adapter 只消费——可被 AI Step/人工/规则共同产出修改，不锁死在代码里 |
| **逃生口** | 平台字段未被标准模型覆盖 | **`platform_raw` JSONB 直通** + Listing 扩展（UCP metadata / Truto JSONata 同哲学） | 平台演进不被标准模型冻结，不另造映射 DSL |

- 贡献者写 Adapter 只需懂「平台 API + 标准模型结构」，**不需要学一套映射 DSL**。

## 4. 插件机制

- **core 零平台依赖**：契约（能力接口 + 标准模型）在 core；core **禁止 import 任何平台 SDK/模型**——core 构建与发布不受平台 SDK 版本/体积影响。
- **每平台 Adapter = 独立 Maven 模块**（自带平台 SDK 依赖树），Java SPI 声明 `PlatformAdapterProvider`（实现清单 = 平台标识 + channel 类型 + 能力接口集合 + 工厂）。
- **加载**：Spring 侧薄加载器扫 SPI 注册为 bean（或 `@ConditionalOnClass` 装配）——Adapter 模块**不依赖 Spring 容器**即可独立演进/测试/被 fork，Spring 生态通过桥接兼容。
- **版本管理双轨**：
  - 契约接口 / 标准模型的 breaking 变更 = core 大版本 + **双跑期**（新旧 Adapter 并存过渡，对齐 #10"type 即隔离边界"哲学）；
  - 平台 API 升级 = Adapter 模块内适配，core 不感知、接口不破。
- **无中央注册表**：core 只认能力接口 + 模块在 classpath 即被发现，不维护"平台清单"（UCP 哲学）。

## 5. 认证收敛（Channel 域）

- **存储**：凭据在 DB，AES 加密负载（对齐 #8 地址加密同源）；channel 实体（CONTEXT：授权 token、店铺维度）挂 `credentials {type, encrypted_payload, status, expires_at}`。
- **刷新职责**：
  - 长期 token（v1 主形态）：手动填入 + `EXPIRED` 状态 + 看板告警人工处理；
  - OAuth refresh 平台（速卖通）：平台专属逻辑在**该平台 Adapter 的 `AuthCapability`**；refresh 触发由 core 统一在调用前 `ensureValid(channel)`，core 只消费"凭据是否可用"，不实现任何平台的刷新协议。
- **CredentialView**：Adapter 接口收到的是解密后的内存对象 + 有效期（不传明文 token 字符串，防日志泄漏）。
- **签名差异**（1688 自定义签名、淘宝/速卖通 OAuth header）= 平台知识 → Adapter 内实现，core 不感知。

## 6. 错误契约与限流

```
AdapterException {
  kind: RETRYABLE | NON_RETRYABLE | AMBIGUOUS   # #11 三类
  platform_code?: string                        # 平台原始错误码（落 reason）
  message: string                               # 可读描述
  retryable_after?: duration                    # 平台 Retry-After/节流窗口（供退避）
}
```

- **所有能力接口只抛 `AdapterException`**，不裸抛平台 SDK 异常。映射语义：
  - `RETRYABLE`（限流 429/5xx/网络抖动）→ workflow Activity RetryPolicy（#11；`retryable_after` 供指数退避参考）；
  - `NON_RETRYABLE`（业务拒绝：资质/类目违规/参数非法）→ `NonRetryableErrorTypes` + Saga（#11）；`platform_code` + `message` 落投影 `last_error`/`terminal_reason` 供看板与人工；
  - `AMBIGUOUS`（超时/连接断开，不知是否生效）→ **不重发**，触发 `PublishCapability.reconcile` 路径；未实现 reconcile 或查询无果走保守路径（#11 Q3）。
- **限流治理归属 Adapter 内**：每平台 token bucket（速卖通 QPS≈5、淘宝 1000/200 rpm、1688 类目 QPS≤10——平台节奏知识自治），超限**本地排队**而非直接抛 429；core 不配平台限流参数——Adapter 是唯一懂"这个平台能打多快"的地方。
- **非 `AdapterException` 的异常 = bug 而非平台错误**：core 侧拦截并按 NON_RETRYABLE 收口（防把代码缺陷当临时故障重试到死）。

## 7. 测试策略与社区验收门槛

core 提供测试基座，无真实账号（速卖通仅企业接入、个人无法实测）下保证 Adapter 质量基线：

1. **双向 fixture**（硬门槛）：每平台 Adapter 必带两套 fixture——`platform→standard`（平台 JSON 响应 → 期望标准模型）与 `standard→platform`（标准模型 → 平台请求体）；core 提供**契约校验器**（用 #7/#8/#10 的 JSON Schema 校验 Adapter 输出/输入），fixture 即"贡献者承诺的映射语义"。
2. **模拟平台**：WireMock/本地 stub server 按 fixture 返回，Adapter 测试不依赖真实网络；VCR 回放（真实调用录制）作进阶可选，非门槛。
3. **错误映射测试**（硬门槛）：至少 RETRYABLE（限流响应）/ NON_RETRYABLE（业务拒绝码）/ AMBIGUOUS（超时）各一例——验证 Adapter 正确翻译平台错误到统一异常契约。
4. **认证接入说明**（硬门槛）：README 写清开发者如何配置测试凭据。

**PR checklist**：① 双向 fixture 全过（core 契约校验）；② 三类错误映射测试齐备；③ README 认证接入说明；④ 不破坏 core 构建（core 零平台依赖，adapter 编译失败不影响 core 发布）。

## 8. 与既有契约的对齐

- **#7 商品**：Listing 是铺货 workflow 唯一输入，Adapter 的 `PublishCapability` 消费 ListingView（改写稿/类目/属性/规格映射/SKU 售价都是**数据**不是代码）；MediaAsset `platform_media_id` 由 PublishResult 回填。
- **#8 订单**：`OrderSyncCapability` 走"拉取为真相"单写入路径（webhook 只唤醒拉取）；游标（ChannelSyncState）由 core 传 `SyncCursor`；地址解密 = `AddressCapability`，触发点在下采购单前；RMA = `RmaCapability` 只读 + OrderRMA 状态机联动。
- **#11 幂等**：错误三类在 Adapter 层就是 `AdapterException.kind`——workflow 的 RetryPolicy / NonRetryableErrorTypes / reconcile 分支直接消费，无需再翻译；`reconcile` = `PublishCapability.reconcile`（可选实现）。
- **#10 消息**：领域事件由 workflow 层广播（`listing.published`/`order.paid` 等），Adapter **不直接发事件**——它是 workflow 的 activity 执行体，事件在 workflow 业务逻辑里发（money 不作 first write、落库后才广播）。Adapter 无消息总线感知。
- **凭证加密**与 #8 地址解密共用同一加密源（AES 密钥管理单一职责）。

## 9. 遗留 fog（实现期回填）

- 各平台 API → 能力接口的**逐项能力映射表**（哪些平台实现哪些 Capability、平台 API 版本/端点差异）——需按真实平台文档逐平台核实（#2/#3 research 已提供初步锚点）。
- 每平台 token bucket 的具体参数与退避档位——以平台官方限流文档为准微调（Adapter 内自治，不升 core）。
- `platform_raw` 逃生口的保留/截断策略（体积与查询需求平衡）——实现期定。
