# 订单标准数据模型：快照原则落为"一等建单快照 + 事件史"、采购单/售后为独立实体

订单模型定为：**Order = 身份/关联/派生态**（`(channel, platform_order_no)` 唯一 + canonical 履约轴派生 + 平台状态原文旁路）；**OrderSnapshot = 一等不可变实体**（下单时固化商品行/单价/数量/优惠/运费/地址掩码/平台原文，此后不改价影响）；变更史**不自建 append-only 版本表**——不可变真相 = Temporal Event History（ADR-0002），业务侧 = "快照基线 + 事件史 + 薄投影"；**PurchaseOrder = 一等实体，Order → 1:N**（1688 下单仅限同供应商，跨供应商必须拆单，采购单自有状态轴/workflow）；**售后收敛为 OrderRMA 单一子实体**（`type = REFUND | DISPUTE`，国内退款与跨境 dispute 两种入口，流程差异全收状态机与 adapter）；**收货地址 = 敏感附属字段、在快照不可变承诺之外**（MASKED→DECRYPTED 解密状态机，下采购单前取明文 AES 落库）。背景：快照原则是用户铁律；真实平台 shape 显示平台侧原生就有下单快照概念（淘宝 `snapshot_url`/1688 `buyerView`）、跨供应商拆单是硬约束、速卖通有 webhook 而淘宝/拼多多只有轮询、速卖通 dispute 是独立域（可冻资）、地址默认脱敏须解密 API。理由见规范 [0003](../specs/0003-order-model.md)，契约草案：[`schemas/order.schema.json`](../../schemas/order.schema.json)。

## Considered Options

- **纯事件溯源：订单数据 = 事件流，无快照实体**（否决）：v1 需要 order 主记录做对账/查询/幂等锚（`(channel, platform_order_no)` unique），纯事件流把简单查询逼进重放；且外部订单是"被动接收"而非"我方主动发起"，建单瞬间固化的业务上下文（商品/价格原文）必须落一个不可变对象——事件流只能表达"变更"，表达不了"下单时点的事实全集"。折中 = 快照基线（事实）+ 事件史（变更真相）双持。
- **每次状态变更落新快照版本（v1/v2/v3 表）**（否决）：铁律字面"用新快照表达变更"的朴素实现，但 v1 无逐版本回放诉求，版本表制造写放大与投影冗余；Temporal Event History 已保证不可变可重放，投影反映最新态即可，需审计时从事件史重建。
- **webhook 与轮询双通道各自建单**（否决）：同一订单两个写入通道 = 两套去重逻辑 + 脏态可能。统一为 webhook（速卖通）= 触发信号、拉取 = 数据真相的单写入路径，重复由游标 + execution 级幂等吸收。
- **dispute / refund 分实体建模**（否决）：跨境 dispute 与国内退款业务动作同质（买家要钱/要退/申诉），分实体把 adapter 与状态机复制两套；收敛为 OrderRMA 单实体、两种入口，跨境 dispute→refund 转化是状态迁移而非换实体。
- **逐平台全量状态翻译**（否决）：五个平台状态枚举互不兼容（速卖通 11+ 态/淘宝退款 6 态），全量映射表是持续维护的负债；canonical 只需覆盖我方决策分支，平台原文旁路保留细粒度供 adapter/人工消费。

## Consequences

- 订单 workflow 读快照不读活商品表——商品改价/SKU 变更永不污染历史订单；履约推进（解密地址/采购/物流）在快照之外累积，不违反不可变承诺。
- 一销售订单拆多采购单 = 多 PurchaseOrder workflow 并行履约，销售侧 SHIPPED/COMPLETED 由采购集合派生，无双写。
- 幂等建单两级（execution 级 + DB unique）吸收 webhook 重推/游标重读/手工重放，对账有硬锚。
- 平台细粒度状态留在原文（`platform_status` + JSONB `platform_raw`），adapter 消费原文、canonical 由 workflow 派生，状态机不随平台 API 变化膨胀。
- 地址明文 AES 加密落库、解密延后到履约前，敏感面最小化；看板只见脱敏值。
- 平台字段缺口（拼多多订单结构、物流枚举、退款金额粒度、介入时限）是已识别 fog，随 #9 Adapter 契约票实测回填，schema 不再拍脑袋预填。
