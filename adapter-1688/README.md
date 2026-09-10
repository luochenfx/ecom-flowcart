# adapter-1688

1688 平台 Adapter（**货源侧**）。独立 Maven 模块 + Java SPI（`PlatformAdapterProvider`），
**零 Spring 依赖**——只依赖 `core-contracts`（Capability 接口族 + 标准模型）、JDK 与 Jackson，
可独立编译 / 测试 / fork（ADR-0007）。

新平台贡献者：**照本模块写即可**（端点映射 → 双向 fixture → 三类错误测试 → 认证 README），
PR checklist 见文末「贡献门槛」。

## 1. 能力清单与归属边界

| Capability | 实现 | 1688 端点 | 说明 |
|---|---|---|---|
| `OfferFetchCapability` | ✅（#19 子集 + #23 值侧校准） | `alibaba.product.get`（当前走 `source_ref.url` 直连） | 采集面 `OfferData`；`sourceSpecId` = `skuInfo.skuMap` 条目 `specId` |
| `PurchaseCapability` | ✅（#23） | 下单 / 支付 / 取消 / 物流，见 §2 | 四段独立调用 |
| `AuthCapability` | ✅（#23） | `POST /auth/system.oauth2/getToken` | OAuth2.0 refresh |
| `PublishCapability` / `ShipmentCapability` / `RmaCapability` / `OrderSyncCapability` / `AddressCapability` | ❌ 不实现 | — | 见下 |

**`ShipmentCapability` 不实现（能力归属边界）**：其语义是「供应商物流单号 → **销售平台**」——
`notifyShipment(ShipmentNotification{platform_order_no, tracking_company, tracking_no, tracking_url})`
携带的是**销售平台订单号**，只属于**销售平台侧** Adapter（淘宝 / 拼多多 / 速卖通）。1688 是货源侧，
不存在"向销售平台回传发货"这一动作；1688 侧物流信息由 `PurchaseCapability.fetchLogistics` 承担。
（ADR-0007「能力归属侧别纠偏」）

`Publish` / `Rma` 同理属销售侧；`OrderSync` 面向销售订单；`Address` 解密归销售侧凭据域。
未实现的能力 = core 侧该链路不可用，取用时 `getCapability` 抛 `IllegalArgumentException`。

## 2. 交易面端点映射（参数按官方 apidoc 校准）

| 契约方法 | 端点（namespace:name） | 关键参数 |
|---|---|---|
| `createPurchase` | `com.alibaba.trade:alibaba.trade.fastCreateOrder` | `flow=saleproxy`（一件代发；`general` 普通批发）、`cargoParamList`（`alibaba.trade.fast.cargo[]`：`{offerId, specId, quantity}`）、`addressParam`（`alibaba.trade.fast.address`：四级地址传**文本名**，官方免查地址码） |
| `payPurchase` | `com.alibaba.trade:alibaba.trade.pay.protocolPay.preparePay` | `tradeWithholdPreparePayParam={"orderId":"<platformPurchaseNo>"}` |
| `cancelPurchase` | `com.alibaba.trade:alibaba.trade.cancel` | `webSite=1688`、`tradeID`、`cancelReason`（官方枚举 `buyerCancel/sellerGoodsLack/other`） |
| `fetchLogistics` | `com.alibaba.logistics:alibaba.trade.getLogisticsTraceInfo.buyerView` | `orderId`、`webSite=1688`（可选 `logisticsId`） |
| `refresh` | `POST /auth/system.oauth2/getToken` | `grant_type=refresh_token`、`client_id`、`client_secret`、`refresh_token` |

口径与已知限制（**不是缺陷，是契约现状**）：

- **`payPurchase` 返回 `void`**：免密代扣失败时平台返回签约 / 收银台链接（`alibaba.alipay.url.get`，
  链接 30 分钟有效），但契约无法回传——需要回传属**契约扩展**（按 ADR-0007 走加法），不在本票改 core。
- **`cancelPurchase` 无"原因"入参**：官方 `cancelReason` 必填 → 固定 `other`。
- **`addressParam` 无 `townText` / `phone`**：标准 `DecryptedAddress` 只有省 / 市 / 区 / 详细地址 / 一个电话
  → `townText`（街道）与 `phone`（固话）无源可填，按官方示例可缺省处理；电话落在 `mobile`。
- **幂等键**：官方 `fastCreateOrder` 支持 `outOrderId`（外部单号，可反查），但 `PurchaseDraft`
  未承载该字段 → 本票不传；需要时属契约扩展。
- **采购面无 `platform_raw` 逃生口**：`PurchaseResult` 契约只定义 `platform_purchase_no`，
  订单金额 / 运费 / 支付有效期等未覆盖字段需经 `alibaba.trade.get.buyerView` 订单快照补齐（#22 范畴）。
- **仅同供应商可合单**：跨供应商须由 order 域拆单后逐单调用（官方错误码提示重复货品需累加处理）。
- **`fetchLogistics` 的 `Tracking.company` 恒空**：官方出参（`logisticsId / logisticsBillNo /
  logisticsSteps`）不含承运商名称，不臆造；`status` 取最后一个节点的 `remark`。

### 签名（param2 网关）

`_aop_signature = UPPERCASE(HEX(HMAC-SHA1(appSecret, urlPath + 参数按 key 字典序 key+value 直连)))`，
其中 `urlPath = param2/1/{namespace}/{apiName}/{appKey}`（**不含**前导 `/openapi`）。
除 `_aop_signature` 自身外的全部实际发送参数（含 `_aop_timestamp`、`access_token`）都参与签名。
OAuth 换票端点官方明确**不需要签名**，故不经网关。

## 3. 认证接入

凭据由 **Channel 域** AES 加密落库、解密后以 `CredentialView` 交给 Adapter（specs/0005 §5）——
Adapter **无 AES 密钥**，既不解密也不回封密文。配置形态：

```java
CredentialView view = channel.decrypt(channelId);          // Channel 域解密
Ali1688AdapterConfig config = Ali1688AdapterConfig.of(view);   // 官方网关 + 默认限流
PurchaseCapability purchase = new Ali1688AdapterProvider(config)
        .getCapability(PurchaseCapability.class);
```

`CredentialView.secrets` 键规范（`type = "1688"`）：

| key | 必填 | 用途 |
|---|---|---|
| `app_key` | ✅ | 应用标识（签名 urlPath 末段 / OAuth `client_id`） |
| `secret` | ✅ | App Secret（HMAC-SHA1 密钥 / OAuth `client_secret），**严禁硬编码进代码与日志** |
| `access_token` | ✅ | 用户授权令牌（业务接口必备） |
| `refresh_token` | 仅 OAuth 刷新 | 换票用；长期 token 形态无此键 |

无凭据时（`Ali1688AdapterConfig.unconfigured()`，SPI 无参发现的默认形态）：`OfferFetch` 退化为
`source_ref.url` 直连，`Purchase` / `Auth` 调用即报 `missing-credential`（NON_RETRYABLE）。

**两种 token 形态与刷新**：

1. **长期 token 人工填入（v1 主形态）**：无 `refresh_token`，`AuthCapability.refresh` 报
   `missing-refresh-token`——**须人工在渠道后台重填**；`credentials.status = EXPIRED` 驱动看板告警
   （告警与人工刷新流程在 Channel 域，Adapter 不参与）。
2. **OAuth refresh**：有 `refresh_token` 时 `AuthCapability.refresh` 走 §2 换票端点，返回新
   `CredentialView`（新 `access_token` / `refresh_token` / `expires_at`）；**回写密文由 Channel 域完成**。

`Ali1688Credential.toString()` 显式掩码（appKey 可见，secret / token 掩码）——**不要打印凭据对象**。

## 4. 限流自治

模块内 token bucket（`Ali1688RateLimiter`）：默认 **10 permits/s、桶容量 10**（ADR-0007 记录的
"1688 类目 QPS ≤ 10"）。**超限本地排队**（阻塞到令牌补齐），不抛 429、不升 core。
平台文档调整速率时用 `Ali1688AdapterConfig.withRateLimit(qps, burst)` 调参——core 不配平台限流参数。

## 5. 错误三类映射（只抛 `AdapterException`）

| 平台侧 | kind | 说明 |
|---|---|---|
| HTTP 429 / 5xx | `RETRYABLE` | 带 `Retry-After` 头时写入 `retryableAfter` |
| HTTP 其余 4xx | `NON_RETRYABLE` | `platformCode` = HTTP 状态码 |
| 业务拒绝 `success=false` | `NON_RETRYABLE` | `platformCode` = 官方错误码（`400*`、`FAIL_BIZ_*`…） |
| 官方错误码 `500*` / `*SYSTEM_ERROR*` / `*SYSTEM_BUSY*` / `*ACCESS_LIMIT*` / `*FLOW_LIMIT*` / `*QPS*` | `RETRYABLE` | 平台侧临时故障，不是业务拒绝。**口径刻意收窄**：业务码也常带 `LIMIT`（起批量 / 最大购买量），宽泛匹配会把业务拒绝误判成抖动而重试到死 |
| **写操作**超时 / 连接断开 | `AMBIGUOUS` | 请求可能在途 → **不重发**，触发 reconcile |
| **读操作**超时 / 连接断开 | `RETRYABLE` | 重试安全（不属 AMBIGUOUS） |
| 缺凭据 / 缺必填字段 / 响应非法 | `NON_RETRYABLE` | 配置或代码问题，重试无意义 |

非 `AdapterException` 的异常 = bug（core 侧按 NON_RETRYABLE 收口，防把代码缺陷当临时故障重试到死）。

## 6. 测试：无真实账号可跑

- **fixture 即契约**：`src/test/resources/fixtures/` 下
  `1688-offer-response.json`（采集）、`1688-trade-fastCreateOrder-request.json`（standard → platform）、
  `1688-trade-fastCreateOrder-response.json` / `1688-trade-cancel-response.json` /
  `1688-trade-preparePay-response.json` / `1688-logistics-trace-response.json` /
  `1688-oauth-getToken-response.json`（platform → standard）。
- **WireMock 模拟网关**：`Ali1688PurchaseTest` / `Ali1688AuthTest` 覆盖四段能力 + 三类错误
  （限流 RETRYABLE / 业务拒绝 NON_RETRYABLE / 写在途 AMBIGUOUS 各 ≥1）。
- **确定性单测**：签名（无网络）、限流（时钟可注入）、凭据解析。
- 有凭据时可用沙箱冒烟：把 `Ali1688AdapterConfig.of(view, baseUrl)` 指向沙箱网关即可（可选）。

跑测试：`mvn -pl adapter-1688 -am test`（全量：`mvn clean test`）。

## 7. 贡献门槛：PR checklist 四查

新增 / 修改任何平台 Adapter 时，PR 必须逐条自查（可复制到 PR 描述）：

- [ ] **① 双向 fixture 全过**：platform → standard 与 standard → platform 各有 fixture 与断言；
      标准模型侧经契约校验（core `ContractObjectMapper` / schema 校验器）。
- [ ] **② 三类错误映射测试齐备**：RETRYABLE（限流 / 5xx）、NON_RETRYABLE（业务拒绝）、
      AMBIGUOUS（写在途）各 ≥1；读操作超时不得伪装成 AMBIGUOUS。
- [ ] **③ 认证 README 齐备**：凭据键规范 / 配置方式 / 长期 token 人工刷新 + EXPIRED 告警说明；
      代码中无硬编码密钥，`toString` 掩码。
- [ ] **④ 不破坏 core 构建**：core 零平台依赖，Adapter 编译 / 测试失败不影响 core 发布；
      Adapter 不反向依赖业务模块（ArchUnit 护栏）、不依赖 Spring。

补充自查：未覆盖字段走 `platform_raw` 逃生口；业务映射（类目 / 规格 / 定价）消费 Listing / master
数据而**不内置**在 Adapter 里。
