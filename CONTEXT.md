# ecom-flowcart（一件代发电商自动化）

AI 原生模块化电商自动化工作流（一件代发 dropshipping）设计仓库：采集 → 清洗/AI Step → 铺货 → 订单回传 → 看板，覆盖国内（1688 → 淘宝/拼多多）与跨境（1688 → 速卖通）双链路。目标产物为可开源的 v1 设计包（架构文档 + 标准数据模型 + 机器可读契约）。

## Language

**Listing**:
把某商品（product）发布到某渠道（channel）的一次铺货实例，以 `(product, channel)` 唯一；一 Listing 对应一个 workflow execution。
_Avoid_: 铺货单、上架记录、product posting

**SPU**:
平台无关的 master 商品单元（一组 SKU 的集合），承载 canonical 标题/描述/图片/来源属性/来源类目，绑定唯一货源（source offer）。
_Avoid_: 商品、product（二词过载，SPU 特指 master 层）

**SKU**:
平台无关的规格变体，隶属某 SPU；以结构化 `specs`（规格名/值，如 color=red）标识，承载成本价与货源 skuId。
_Avoid_: variant、规格项（variant 是通用词，SKU 特指 master 变体单元）

**货源（source offer）**:
SPU 数据的来源商品刊登（1688 offer），以 `source_ref`（platform/external_id/url）引用；v1 一 SPU ↔ 一货源。
_Avoid_: 供应商、上游商品（语义不同：货源是数据来源，供应商是经营关系）

**媒体资产（MediaAsset）**:
图片/视频等媒体的一等实体：来源 URL → 我方存储 → 平台 media_id 回填，带处理状态与角色；支持 variant 自引用（图内文本本地化 = `variant_purpose=LOCALIZED` 的独立新资产，不属于文本翻译）。
_Avoid_: 图片、image（媒体不止图；MediaAsset 特指带生命周期/变体的资产实体）

**Channel（渠道账号）**:
目标平台上的店铺账号上下文（授权 token、店铺维度），如"淘宝 A 店"。区别于平台本身（taobao 是 platform，channel 是账号级）。
_Avoid_: 平台、店铺、store、shop account

**铺货（publish）**:
动作：向 channel 发布 product 以创建 Listing 的过程。外部 API 调用，视为必定失败，须幂等 + 重试。
_Avoid_: 上架、发布商品（避免歧义：平台"上架"语义可能指 listing 状态切换）

**Reconcile（核实）**:
在平台侧缺少"按外部引用查已存在商品"能力时，确认一次外部调用是否已生效（平台是否已创建 Listing）的手段；Adapter 可选实现。核实到已生效即达成去重目的。
_Avoid_: 查重（语义不同：reconcile 确认"生效与否"，不假设平台提供查重端点）

**Order（销售订单）**:
顾客在某销售 channel 下达的交易，以 `(channel, platform_order_no)` 唯一；一 Order 对应一个订单 workflow execution。Order 实体只承载身份/关联/派生态，业务明细在它引用的建单快照里。
_Avoid_: 交易、主订单（交易是平台词；Order 特指我方内部销售订单，与 PurchaseOrder 对称）

**建单快照（order snapshot）**:
Order 生成时固化的不可变业务事实（商品行/单价/数量/优惠/运费/平台原文）；此后商品改价、SKU 变更不影响历史订单。变更史不落数据行——不可变真相在 Temporal Event History，投影只反映最新态。
_Avoid_: 订单版本（暗示 append-only 版本表，v1 不建——快照基线 + 事件史即可重建）

**PurchaseOrder（采购单）**:
我方在货源平台（1688）对某供应商下达的采购请求，以 `platform_purchase_no` 唯一；一销售 Order 可拆多张（跨供应商必须拆单）。自有状态轴与 workflow，经订单行关联回销售订单。
_Avoid_: 进货单、供应商订单、代发单（语义窄；PurchaseOrder 是销售履约链的一等环节）

**OrderRMA（售后单）**:
挂在 Order 下的售后/退款/纠纷统一子实体，`type = REFUND | DISPUTE`（国内退款入口 / 跨境纠纷入口）；自有状态轴与 workflow。订单履约轴的 REFUNDING/DISPUTED 只是由它派生的标记。
_Avoid_: 退款单、纠纷单（分实体是平台视角；我方收敛为单一售后实体两种入口）

**Domain Event（领域事件）**:
业务事实已落库后广播的通知（`order.paid` / `listing.published` / `purchase.shipped`…），命名 `<domain>.<entity>.<past-tense>`；总线（RabbitMQ）只承载它——一切"驱动动作"的请求不经总线（走 workflow start/signal）。事件负载是轻量引用，数据真相在业务库，消费端回读。
_Avoid_: 消息、MQ 消息（过载；Domain Event 特指已发生事实的广播，区别于命令/请求）

**envelope（事件信封）**:
总线消息的统一信封：`id/type/version/occurred_at/producer/entity_ref?/correlation_id?/trace_id?/payload`，transport-agnostic（总线可换、契约不破，ADR-0001）。version 语义化：additive 改次版本（新旧共存）、breaking 换 type（旧 type 冻结）——repo 即 registry。
_Avoid_: 消息头、payload wrapper（envelope 是带版本/追踪语义的完整契约，非仅头部）
