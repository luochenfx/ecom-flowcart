# 商品标准数据模型：SPU/SKU 为 master、Listing 为平台特化内容（多 taxonomy 类目 + JSONB 扩展）

商品标准数据模型定为**三层 master/特化分离**：SPU/SKU = 平台无关 master（标题/描述/图片/规格/成本价，canonical i18n），Listing = 平台特化铺货内容（改写稿、目标叶子类目、平台属性值、Listing SKU 售价集），一 SPU ↔ 一货源（`source_ref` 一等字段）。背景：该模型是系统地基（采集/清洗/AI Step/铺货/存储全部围绕它），真实平台 shape 显示类目属性随叶子类目变、速卖通要求多语言多份提交、1688 图不可直链转采。理由：平台相关/可变/随类目变的内容一律不进 master，铺货 workflow 只读 Listing 即可执行（对齐 ADR-0003 一 Listing 一 Execution），master 永不被平台污染。类目**不建内部统一树**（多 taxonomy 挂载：SPU 挂来源类目、Listing 挂目标叶子类目，映射是流程助手逻辑）；扩展字段用 JSONB 键值容器、不开 EAV。多语言 = canonical 结构化 i18n（翻译回填 master，一处翻译多处复用）。**图内文本本地化 ≠ 文本翻译**：属媒体资产（MediaAsset）的 AI 生成变体（`variant_purpose=LOCALIZED` 独立新图），v1 预留 variant 结构、不实现生成任务，待 AI 图像成本下降后引入。细节见[规范 0002](../specs/0002-product-catalog-model.md)，契约草案：[`schemas/product-catalog.schema.json`](../../schemas/product-catalog.schema.json)。

## Considered Options

- **内部统一类目树 + 内部→平台映射表**（否决）：需持续维护一套与任何平台都不一致的自有 taxonomy，v1 无自营团队养不起；1688 采集自带来源类目、目标类目只在铺货时需要，分属 SPU/Listing 两层更自然。对齐 research #6：多 taxonomy 挂载取代 N×N 映射表。
- **EAV 表承载扩展属性**（否决）：查询/迁移成本高，v1 无高频过滤诉求；JSONB 由 Postgres 承载足够，未来真出现高频过滤再针对键建索引列。
- **图内文本本地化并入 i18n 文本体系**（否决）：图上文字不是文本字段，翻译体系管不到图片像素；本地化必然生成新图，属媒体资产变体。若并入文本翻译会错误建模。
- **多货源合并进 v1**（否决）："选哪家发货"是经营决策（价格/质量权衡），无经营背景的 v1 不先建复杂度；`source_ref` 预留一对一，演进为货源可选时不改模型结构。

## Consequences

- Listing 是铺货执行的唯一输入（内容完备），master 纯净可复用；多平台铺货 = 多 Listing 引用同一 SPU/SKU。
- 类目映射正确性下放到铺货流程助手（AI/规则 + 人工确认），平台类目树 API 变化不伤核心模型。
- 多语言翻译一次、多处复用；跨境多语言提交（速卖通 en/ru/es…）从 canonical i18n 取数，不重复烧翻译成本。
- MediaAsset 生命周期（下载/处理/上传）由后台任务驱动 `processing_state`；平台图床差异收敛到 Listing.`platform_media_id` 回填。
- 对象级血缘（`provenance`：父对象引用 + step 枚举）承载审计回溯；不做字段级 lineage。
