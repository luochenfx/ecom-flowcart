# 规范：订单标准数据模型（快照原则落地）

> 来源：[Grilling: 订单标准数据模型——快照原则落地](https://github.com/luochenfx/ecom-flowcart/issues/8)（Part of #1）
> 依赖：[ADR-0002（Temporal）](../adr/0002-temporal-for-workflow-orchestration.md)、[ADR-0003 + 规范 0001（幂等 execution 级）](./0001-listing-publish-idempotency.md)、[规范 0002（商品模型）](./0002-product-catalog-model.md) — Listing/SPU/SKU 引用语义对齐。
> 契约草案：[`schemas/order.schema.json`](../../schemas/order.schema.json)（v0.1.0）
> 状态：v1 设计期决议，随 #9（Adapter 契约）/ #10（Schema 分层）落地时消费。

## 1. 决策概览

- **快照原则落为双层**：① **OrderSnapshot（建单快照）= 一等不可变实体**——下单时固化商品行/单价/数量/优惠/运费/地址掩码/平台原文，此后商品改价/SKU 变更不影响历史订单；② 变更史**不自建 append-only 事件表**——状态迁移的不可变真相 = Temporal Event History（ADR-0002），业务侧 = "快照基线 + 事件史 + 薄投影"。
- **Order = 身份/关联/派生态**：`order_id`（确定性，对 #11 workflowId 语义）+ `(channel_id, platform_order_no)` 唯一 + 平台状态**原文旁路** + canonical 履约轴（**派生**，投影层落库）+ 关联引用（snapshot / lines / rmas）。可变业务明细一律不进 Order，在快照。
- **PurchaseOrder（采购单）= 一等实体，Order → PurchaseOrder 1:N**：1688 `fastCreateOrder`（`flow=saleproxy`）仅限同供应商 → 跨供应商必须拆单；采购单自有状态轴与 workflow（`purchase-{...}`），经 `order_line_id` 关联回销售行。
- **状态机双轴 + 平台旁路**：不逐平台全量翻译状态。销售侧一条 **canonical 履约轴**（决策逻辑用）+ 平台原始状态原文保留；采购侧独立 canonical 轴。canonical 一律由 workflow 从平台事件**派生**（对齐 UCP"status 派生防漂移"），投影层落库、可覆盖。
- **双向同步 = 单写入路径**：**webhook = 触发信号，拉取 = 数据真相**。所有订单入库一律走增量拉取；webhook（速卖通）验签后只作唤醒，轮询（淘宝/拼多多）周期性拉取；两路触发汇合到同一入口（按 `(channel, 游标)` 拉取 → 逐单 start `order-{platform}-{orderNo}`）。
- **幂等建单 = 两级，不建幂等表**：① 确定性 workflowId 吸收跨通道重复（webhook 重推/游标重读/手工重放）；② DB 层 `(channel_id, platform_order_no)` / `(platform_order_no, platform_line_id)` unique constraint 兜底（对账硬锚）。
- **收货地址 = 敏感附属字段（快照不可变承诺之外）**：`state: MASKED|DECRYPTED|EMPTY` + AES 加密明文负载；解密发生在**下采购单前**（1688 直发顾客需要明文）。快照不可变性只覆盖**已发生的交易事实**，履约可后补的操作数据不在其内。
- **售后收敛为 OrderRMA 单一子实体（Order 1:N）**：`type = REFUND | DISPUTE`（国内退款 / 跨境纠纷两种入口）+ 自有状态轴 + 行关联（支持部分退款）+ 平台侧 ID（`refund_id`/`issue_id`）+ 自有 workflow。订单履约轴的 `REFUNDING`/`DISPUTED` 只是**派生标记**，非实体。

## 2. 实体分层与内容边界

```
Order (身份/关联/派生态)
├── order_id / channel_id / platform / platform_order_no
├── platform_status (+time)          # 平台原始状态旁路，不做翻译
├── platform_raw (JSONB)             # 平台订单原文，审计/对账
├── fulfillment_status               # canonical 履约轴（派生，投影层落库）
├── snapshot_id → OrderSnapshot      # 业务事实不可变源
├── shipping_address                 # 敏感附属字段（快照承诺之外）
├── lines[] → OrderLine              # 行级履约态
└── rmas[] → OrderRma

OrderSnapshot (一等不可变，不 UPDATE 业务事实)
├── amounts: 商品总额/运费/优惠/实付（粒度按平台能给，缺口 platform_raw 兜底）
├── line_snapshots[]                 # 行级事实：外部商品引用 + 标题/规格原文 + 单价/数量/行小计
├── shipping_address_mask            # 下单时平台给的（可能脱敏）原样
└── platform_raw (JSONB)             # 平台原文快照（淘宝 snapshot_url / 1688 buyerView 同源概念）

OrderLine (履约身份，业务事实在快照)
├── platform_line_id                 # 行级幂等锚
├── listing_ref?                     # 本行买的是哪个 Listing 的货（回指铺货产出）
└── purchase_line_refs[]             # 关联采购行（1 销售行 → N 采购行：跨供应商拆单）

PurchaseOrder (一等实体，自有生命周期/workflow，Order → 1:N)
├── supplier_ref (1688 卖家)         # 一采购单仅限同供应商
├── platform_purchase_no (1688 单号)
├── purchase_status                  # 采购 canonical 轴（PENDING_PAYMENT/PAID/SHIPPED/…）
├── lines[] (order_line_ref → 销售行) 
├── amount / tracking[]              # 供应商发货物流（logistics.trace 回填）
└── 支付/取消/售后与销售订单解耦

OrderRma (售后统一子实体，Order 1:N，自有 workflow)
├── type = REFUND | DISPUTE          # 国内退款入口 / 跨境纠纷入口（速卖通 issue 可冻资）
├── platform_rma_id (refund_id / issue_id)
├── rma_status                       # canonical 售后轴
├── lines[] (部分退款支持) / amount / reason
└── 流程差异（平台介入时限/转化）全收进状态机与 adapter

ChannelSyncState (每 channel 一行的增量游标)
└── cursor (JSONB)                   # 纯位置指针，无状态机逻辑，不进 Temporal
```

**边界规则**：业务事实（下单时已确定的商品/价格）只进快照；履约推进才补齐的操作数据（解密地址、采购关联、物流单号）在 Order/OrderLine/PurchaseOrder；状态演进不落数据行（事件史是真相，投影只反映最新态）。

## 3. 快照模型（铁律落地）

- **不可变对象只有一个：OrderSnapshot**。建单时由同步入口一次性写入，业务事实字段（行/价/优惠/原文）此后**不可 UPDATE**。
- **变更不产生新快照版本**：后续状态演进（发货/完成/退款）走 workflow 事件史（Temporal 为真相，ADR-0002），投影表（execution_projection `type='order'` 行）只承载最新态供看板/列表查询。若未来审计需要逐版本回放，从事件史重建即可——**不需要 v1 建快照版本表**。
- 平台侧的同类机制（淘宝 `snapshot_url`、1688 `buyerView` 固化、UCP item 快照）说明该方向是平台共识，非自造。

## 4. 状态机（双轴 + 派生）

- **销售履约轴（canonical 枚举 v1 草案）**：`PENDING_PAYMENT / AWAITING_PURCHASE / PURCHASING / PARTIALLY_SHIPPED / SHIPPED / COMPLETED / CANCELLED / REFUNDING / DISPUTED / UNKNOWN`。
- **采购轴（PurchaseOrder 上）**：`PENDING_PAYMENT / PAID / SHIPPED / COMPLETED / CANCELLED / REFUNDING / UNKNOWN`。
- **平台旁路**：各平台原始状态原文保留（`platform_status` + time），**不做逐平台全量翻译**——canonical 只需覆盖我方决策分支（要不要采购/能不能发货/是否完结），平台细粒度（速卖通 `RISK_CONTROL`/`IN_FROZEN`、淘宝退款 6 态等）留在原文供 adapter 与人工消费。
- **派生纪律**：canonical 由订单/purchase workflow 从平台事件计算后写投影，不设"我方手工改 canonical 状态"的写入路径（对齐 UCP 防漂移）；销售侧 `REFUNDING/DISPUTED` 由 RMA 存在派生，不反向写 RMA。
- 两轴各自由自己的 workflow 维护，**不互相写对方状态**；销售履约进度 = 其下采购单集合的派生聚合（全部发货才 SHIPPED、部分发货 PARTIALLY_SHIPPED）。

## 5. 双向同步（单写入路径）

- **数据真相 = 拉取**：订单入库只有一条路径——按 `(channel, 增量游标)` 拉取 → 逐单 start `order-{platform}-{orderNo}` workflow。
- **webhook = 触发信号**：速卖通 message subscription 到达 → 验签 → 作为"该订单需要拉取"的唤醒（不直接建单，不做第二写入通道）。淘宝/拼多多无 webhook → 发现层按各自限频周期性增量拉取（淘宝 1000/200 rpm、拼多多按量计费）。
- **游标存储**：`ChannelSyncState`（每 channel 一行，DB）——纯位置指针，无状态机逻辑，不进 Temporal；发现层（周期性扫描）以每 channel 一个调度 workflow 承载。
- **重复吸收**：webhook 重推 / 轮询游标重读由"游标推进 + execution 级幂等"吸收，不存在双通道各建一单的脏态。

## 6. 幂等建单（两级，不建幂等表）

- **第一级（入口）**：确定性 `workflowId = order-{platform}-{orderNo}`。重复 start 命中既有 Execution → 返回既有（吸收 webhook 重推/游标重读/手工重放）。
- **第二级（DB）**：order 主表 `(channel_id, platform_order_no)` unique constraint；行表 `(platform_order_no, platform_line_id)` unique constraint——防绕过 workflow 的并发写入，给对账硬锚。
- 与 #11 的差异说明：铺货是"我方主动发起"，可完全依赖 execution 级幂等；订单是"外部先发生、我方被动接收"，入口重复来源更多样，故加 DB 唯一约束做双保险（订单是支撑对账/查询的核心记录，成本极低）。

## 7. 敏感字段（收货地址）

- 地址 = order 上的 `shipping_address`（独立于快照承诺）：`state`（`MASKED` 平台给了脱敏值 / `DECRYPTED` 已解密加密落库 / `EMPTY` 未给）+ `encrypted_payload`（AES-256-GCM）+ `masked`（当前可见脱敏地址，供看板）。
- **解密时机**：下采购单前触发平台解密 API（速卖通 `trade.seller.order.decrypt` / 淘宝 OAID 电子面单流程）→ 明文 AES 加密落库 → 状态置 `DECRYPTED`。1688 直发顾客的 `addressParam` 消费该明文。
- 边界：快照 `shipping_address_mask` 固化的是"下单时平台给的（可能脱敏）原样"，属不可变业务事实；`shipping_address` 的明文/解密态是可后补的操作数据——两者分置，互不污染。

## 8. 售后 / 退款 / 纠纷（OrderRMA）

- **单一实体收敛**：不分 dispute/refund 两套。`type=REFUND`（国内退款入口，淘宝 6 态/拼多多 refund）/ `type=DISPUTE`（跨境纠纷入口，速卖通 issue：独立生命周期、可冻资、平台介入时限）。跨境 dispute → refund 转化 = RMA 内状态迁移，不是换实体。
- **与采购侧联动**：退货给 1688 供应商 = 另一张采购售后（PurchaseOrder 侧独立处理），经 `order_line_ref` 关联，不在 OrderRMA 内建模采购退款。
- 订单履约轴 `REFUNDING`/`DISPUTED` 由 RMA 存在**派生**，非独立写入。

## 9. 与既有契约的对齐

- **order_id / purchase_order_id / rma_id** 均为确定性 id（workflowId 的业务键），与 #11 `listing_id` 语义一致，避免另造键。
- **listing_ref**：订单行回指铺货产出（本订单买的货来自哪个 Listing）——打通"铺货 → 出单"闭环，订单回传模块以 `platform_item_id`（商品模型/规范 0001 §8）关联。
- **provenance**：复用商品模型 `Provenance`（schema 跨文件 `$ref`），`created_by_step` 增补 `ORDER_SYNC`（建单入口）、`PURCHASE`、`FULFILL` 等订单侧 step（#10 Schema 分层时统一枚举）。
- **Channel / channel_id**：引用渠道账号（CONTEXT.md），凭据归 #9。
- 平台订单字段缺口（拼多多订单字段级结构、各平台物流公司枚举/单号格式、退款金额粒度、平台介入时限）→ 设计期已识别为 fog，消费时以 adapter 实测为准，勿拍脑袋填 schema。
