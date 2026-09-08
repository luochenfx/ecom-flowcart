# ecom-flowcart（一件代发电商自动化）

AI 原生模块化电商自动化工作流（一件代发 dropshipping）设计仓库：采集 → 清洗/AI Step → 铺货 → 订单回传 → 看板，覆盖国内（1688 → 淘宝/拼多多）与跨境（1688 → 速卖通）双链路。目标产物为可开源的 v1 设计包（架构文档 + 标准数据模型 + 机器可读契约）。

## Language

**Listing**:
把某商品（product）发布到某渠道（channel）的一次铺货实例，以 `(product, channel)` 唯一；一 Listing 对应一个 workflow execution。
_Avoid_: 铺货单、上架记录、product posting

**Channel（渠道账号）**:
目标平台上的店铺账号上下文（授权 token、店铺维度），如"淘宝 A 店"。区别于平台本身（taobao 是 platform，channel 是账号级）。
_Avoid_: 平台、店铺、store、shop account

**铺货（publish）**:
动作：向 channel 发布 product 以创建 Listing 的过程。外部 API 调用，视为必定失败，须幂等 + 重试。
_Avoid_: 上架、发布商品（避免歧义：平台"上架"语义可能指 listing 状态切换）

**Reconcile（核实）**:
在平台侧缺少"按外部引用查已存在商品"能力时，确认一次外部调用是否已生效（平台是否已创建 Listing）的手段；Adapter 可选实现。核实到已生效即达成去重目的。
_Avoid_: 查重（语义不同：reconcile 确认"生效与否"，不假设平台提供查重端点）
