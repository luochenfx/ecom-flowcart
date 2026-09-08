# 规范：商品标准数据模型（SPU / SKU / Listing / 媒体资产）

> 来源：[Grilling: 商品标准数据模型——SPU/SKU/类目映射](https://github.com/luochenfx/ecom-flowcart/issues/7)（Part of #1）
> 依赖：[ADR-0002（Temporal）](../adr/0002-temporal-for-workflow-orchestration.md)、[ADR-0003 + 规范 0001（铺货幂等）](./0001-listing-publish-idempotency.md) — Listing 语义与 execution 对齐。
> 契约草案：[`schemas/product-catalog.schema.json`](../../schemas/product-catalog.schema.json)（v0.1.0）
> 状态：v1 设计期决议，随 #9（Adapter 契约）/ #10（Schema 分层）落地时消费。

## 1. 决策概览

- **SPU/SKU = 平台无关 master**（采集原貌 + 我方清理后的 canonical：标题/描述/图片/规格/成本价）。
- **Listing = 平台特化铺货内容**：改写标题/描述、目标叶子类目、平台属性值、Listing SKU 集（引用 canonical SKU + 平台售价，可只铺部分规格）。铺货 workflow 只读 Listing 即可执行——master 永不被平台污染。
- **一 SPU ↔ 一 source offer（1688）**：`source_ref` 为一等字段（platform/external_id/url/fetched_at）；多货源合并为后续演进，v1 不建模。
- **不建内部统一类目树**：多 taxonomy 挂载。SPU 挂**来源平台类目**（`source_categories`）；Listing 挂**目标平台叶子类目**（`platform_category`，铺货时选择/推导）。类目映射是流程助手逻辑，不入核心表。
- **扩展字段 = JSONB 键值容器，不开 EAV**：SPU 存**来源属性快照**（`attributes`），Listing 存**平台属性值**（`platform_attributes`，含平台属性 ID）。
- **SKU 规格结构化**：`specs: [{name, value}]` canonical 语义（color/size…），跨平台稳定引用；canonical 规格名 → 平台属性 ID 的**重映射放 Listing**（`spec_mappings`）。
- **多语言 = canonical 结构化 i18n**：`titles/descriptions` 为 `Map<locale,string>`；AI 翻译**回填 canonical**（一次翻译多处复用）；Listing 只声明用哪些 locale + 平台改写覆盖。
- **媒体 = MediaAsset 实体**：`source_url` → `storage_ref`（下载/处理）→ 平台 `media_id` 回填，`processing_state` 由后台任务改；支持 **variant 自引用**（`variant_of`/`variant_purpose`/`variant_locale`）。
- **血缘 = 对象级**：`provenance`（父对象引用 + `created_by_step`/`updated_by_step`），不做字段级溯源。

## 2. 模型分层与内容边界

```
SPU (master, 平台无关)
├── titles/descriptions: Map<locale,string>   # canonical i18n，至少含来源 locale
├── images[]: MediaRef                         # 主图/轮播/详情图
├── source_categories[]: CategoryRef           # 来源平台类目（多 taxonomy）
├── attributes[]: Attribute                    # 来源属性快照（JSONB 键值）
├── source_ref: SourceRef                      # 唯一货源（1688 offer）
└── skus[]: SkuRef

SKU (master 规格变体)
├── specs[]: SpecValue                         # 结构化规格（canonical 语义）
├── cost_price: Money                          # 成本价（我方）
├── barcode / images[]（变体图，可选）
└── source_sku_id: string                      # 1688 skuMap 原始 skuId

Listing (平台特化铺货内容，一 Listing ↔ 一 execution，对齐 #11)
├── title_overrides / description_overrides: Map<locale,string>  # 平台改写（AI/人工）
├── locales[]                                    # 提交用 locale 集
├── platform_category: CategoryRef               # 目标平台叶子类目
├── platform_attributes[]: PlatformAttribute     # 目标类目属性值（平台属性 ID）
├── spec_mappings[]: SpecMapping                 # canonical 规格名 → 平台属性 ID
├── sku_set[]: ListingSku                        # 引用 canonical SKU + 平台售价 + enabled
├── images[]: ListingImage                       # 引用 SPU 资产或覆盖图 + platform_media_id 回填
└── provenance（父 = spu_id）
```

**边界规则**：凡平台相关、可变、随类目变的内容一律不进 SPU/SKU；SPU/SKU 只承载跨平台稳定的 canonical 事实。改写/售价/类目/属性/媒体回填全部进 Listing。

## 3. 类目体系（多 taxonomy 挂载）

- 不维护内部统一类目树。类目以 `CategoryRef {taxonomy, value, label}` 表达，`taxonomy` 标识来源体系（`1688`/`taobao`/`pdd`/`aliexpress`…）。
- SPU 记录来源类目（采集即得）；Listing 记录目标叶子类目（铺货时从来源类目推导或人工选）。
- **映射 = 流程助手**：推导逻辑（来源类目 → 候选目标叶子类目）属铺货流程/AI 建议，不落核心表；平台类目树 API 变化不影响核心模型。
- 类目属性差异：属性 schema 由**平台侧类目模板**给出（各平台 `getchildattributes` / `itemprops` 类 API），核心模型只存"该 Listing 填的属性值"（`platform_attributes`），不存属性 schema。

## 4. 扩展字段策略

- 核心模型只保留跨平台稳定字段；一切随类目变/平台特有的字段进 **JSONB 键值容器**：
  - SPU.`attributes`：来源属性快照（1688 采集原样，保留原始键名）。
  - Listing.`platform_attributes`：目标类目属性值，含 `platform_attr_id`。
- 不开 EAV 表（查询/迁移成本高）；JSONB 由 DB（Postgres）承载。若未来出现高频过滤需求，再针对具体键建索引列（演进，非 v1）。

## 5. 多语言

- canonical `titles/descriptions` = `Map<locale,string>`；来源 locale（中文）必填。
- AI 翻译产物**回填 canonical**（一处翻译、多处复用）——速卖通一次铺货要提交 en/ru/es 等多份，逐 Listing 翻译会重复烧钱。
- Listing 用 `locales[]` 声明提交哪些语言，`title_overrides`/`description_overrides` 承载平台改写（AI Step 针对平台风格优化，非纯翻译）。
- **图片上的文本不属文本翻译体系**（见 §6 variant 预留）：图内文字本地化 = 生成一张新图，不是 i18n 字段的职责。

## 6. 媒体资产与 variant 预留

- `MediaAsset`：`source_url`（1688 引用）→ `storage_ref`（我方下载/处理后存储）→ `processing_state`（`RAW/DOWNLOADED/PROCESSED/UPLOADED/FAILED`）+ `role`（`MAIN/GALLERY/DETAIL/VIDEO`）。下载/处理/上传是后台任务，模型只存引用与状态指针。
- 平台图床差异与 `platform_media_id` 回填在 Listing.`images[].platform_media_id`（铺货 workflow 写）。
- **variant 自引用（演进预留）**：`variant_of`（父资产 id）+ `variant_purpose`（`LOCALIZED` = AI 生成图内文本本地化变体 / `GENERATED` / `CROPPED`）+ `variant_locale`（变体面向语言）。
  - **决策**：图内文本本地化（商品图上的中文 → 目标语言）属**媒体资产的 AI 生成变体**——生成的是**独立新 MediaAsset**（`variant_purpose=LOCALIZED`），**不进入文本 i18n 体系**。
  - v1 **不实现**该生成任务（AI 图像生成成本仍高）；模型已预留 variant 结构与 `variant_purpose=LOCALIZED` 枚举，成本下降后引入生成流水线即可，不改模型。
  - 翻译回填 canonical（§5）与图内文本本地化（§6）是两条正交路径：前者动文本字段，后者产新图片资产。

## 7. 数据血缘（对象级，最小形态）

- 每对象带 `provenance`：`created_by_step`/`updated_by_step`（`CAPTURE/CLEAN/AI/LISTING/RECONCILE/HUMAN`）+ 时间戳 + `parent_ref`（Listing→spu_id、SPU→source offer）。
- 不做字段级溯源/lineage 图（v1 过重）；审计需求沿 step 链 + 对象引用回溯，属看板/查询层消费（execution_projection 的 `last_error` 等另行承载执行态）。

## 8. 与既有契约的对齐

- **Listing id 语义**：`listing_id` 即 #11 确定性 workflowId 的业务键（`listing-{productId}-{channelId}`）——商品模型里的 `listing_id` 与之对应，避免另造键。
- **铺货结果回填**：`platform_item_id`（平台商品级 id）由铺货 workflow 写 `execution_projection`（规范 0001 §8），Listing 实体不含执行状态——`provenance` 只记业务血缘，不记执行态。
- **Channel**：`channel_id` 引用渠道账号（CONTEXT.md），具体实体/凭据归 Adapter 契约票（#9）。
