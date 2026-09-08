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
