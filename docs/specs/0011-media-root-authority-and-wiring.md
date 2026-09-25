# 规范 0011：媒体根权威来源与接线（env 唯一权威 + compose 接线）

> 来源：GitHub issue [#96『Chore: 媒体根未接线（声明未被 app 代码消费，容器内落不可写相对路径）』](https://github.com/luochenfx/ecom-flowcart/issues/96)（state=OPEN，label=`needs-triage`）
> 落库票：[issue #128](https://github.com/luochenfx/ecom-flowcart/issues/128)（本规范进入 main 的承载票，docs-only）
> 依赖：[规范 0007（端到端链路打通）](./0007-end-to-end-flow-assembly.md) §7.2（`app.media-root` 声明面）；[规范 0008（CI 镜像构建守门）](./0008-ci-image-build-guard.md) §5.5 / §10 R3、R4、R5（`/data/media` 纳入守门路径集合的判据来源与改述活口）；[规范 0009](./0009-ci-required-status-gate.md) §13（本项登记为「另立票」）；ADR-0007（Adapter/AI Step 插件化装配）；ADR-0009（模块化单体 + 部署视图）
> 上游决策记录：**`DR-FLOWCART-0096-01`**（决策人：甄定之；Phase 2 / round 1）；决策日志 `.decisions/96-media-root-decision-log.md`（主理人 append-only 维护）
> 状态：**规范态 · 待落地**（本规范只定义决策与验收线；实现由 spec 定稿后的 build 票承担；**本规范不改任何实现文件**）
> 范围声明：本规范只解决「**媒体根的权威来源裁定 + 接线方式**」与「**守门判据的口径改述**」。以下明确**不在范围**：① 任何 CI 断言的新增/修改（守门脚本一字不动）；② `CONTEXT.md` 任何改动（术语登记只落在本规范内）；③ 规范 0007 正文键位改动；④ #75 e2e 编排；⑤ 镜像内容架构。

---

## 1. 元信息

| 项 | 内容 |
|---|---|
| spec 编号 | **0011**（`docs/specs/` 现有最大编号 = 0010，一手确认，非臆测） |
| 文件名 | `docs/specs/0011-media-root-authority-and-wiring.md` |
| 命题载体 | GitHub issue #96（`needs-triage`，问题登记票） |
| 落库票 | GitHub issue #128（本规范进入 main 的承载票；docs-only） |
| 决策记录 | `DR-FLOWCART-0096-01`（`need_human = false`，综合置信 0.85） |
| 时态 | 规范态 · 待落地 |
| 写面 | 仅本 spec 文件（落地由 build 票承担，见 §11） |

---

## 2. 问题陈述（实现态事实链）

三段因果链（**语义锚点为主，行号仅参考且「行号易腐」，承 0008 §11 注教训**）：

1. **声明未消费**：`application.yml` 的 `app` 节声明 `app.media-root`（语义锚点：`application.yml` → `app:` → `media-root` 键）；该键以 `@ConfigurationProperties(prefix = "app")` 绑定到 `AppProperties.mediaRoot`（语义锚点：`AppProperties` 字段 `mediaRoot` + getter/setter），但 `AppProperties` 类 javadoc **自认**「`app.media-root` 与 `app.flow.channels` 是 spec §7.2 声明的配置面，**v1 尚未由 app 代码读取**」。⇒ 全仓 main 源集无任何消费者。
2. **env 未接线**：生产媒体根由内容 SPI 实现从其**环境变量**读取 —— `ContentAiStepProvider.ENV_MEDIA_ROOT = "FLOWCART_MEDIA_ROOT"`（语义锚点：`ENV_MEDIA_ROOT` 常量），ServiceLoader 生产入口 = **无参构造**（语义锚点：`ContentAiStepProvider()` 无参构造 → `defaultMediaProcessor()` → `System.getenv(ENV_MEDIA_ROOT)`）；缺省值为**相对路径** `DEFAULT_MEDIA_ROOT = "target/media"`（语义锚点：`DEFAULT_MEDIA_ROOT` 常量）。而 `docker-compose.yml` 的 **`app` 服务无 `environment:` 段**（语义锚点：compose `services.app`）⇒ `FLOWCART_MEDIA_ROOT` 未接线。
3. **相对缺省不可写**：`Dockerfile` 的 runtime 段预建 `/data/media` 与 `/data/flowcart` 并 `chown -R flowcart:flowcart /data`（语义锚点：`Dockerfile` runtime 段 `RUN mkdir -p … && chown -R …`），随后 `WORKDIR /app`（语义锚点：`WORKDIR`）与 `USER flowcart`（语义锚点：`USER`）⇒ 容器内媒体根落 `/app/target/media`，而 `/app` 属 `root:root` ⇒ 非 root 运行用户**实测不可创建**（`ArchiveMediaProcessor.process` 内 `Files.createDirectories(root)`，语义锚点：`ArchiveMediaProcessor` → `process` → `Files.createDirectories(root)`）；内容链 `media.process` 一旦执行即失败。

**本仓「真实写入目标」一词的歧义**（承 0008 §10 R4）：① 已接线写入目标 = `app.data-root`（语义锚点：`WorkerServiceConfiguration` → `JsonFileOrderStore` / `JsonFilePublishStateStore`，有代码真写）；② 已声明数据根 = `app.media-root`（声明未接线）。本歧义即本规范 §8 术语登记的对象。

---

## 3. 事实订正说明（**必须如实保留**）

**订正对象**：round 1 拷问包把规范 0008 §6 的「三路径 union」（`/data` + `/data/media` + `/data/flowcart`）当作**实现态**引用。裁决官甄定之订正之；主理人已用**三条独立通道**复算，**订正确认成立**：

| 通道 | 命令 / 引用（一手复算） | 结果 |
|---|---|---|
| ① 读实现文件 | 读 `.github/workflows/ci.yml` 的 `Assert runtime contract (non-root + /data writable)` step（行号参考 `125-132`，**行号易腐**） | 脚本体仅 `touch /data/.writecheck && rm /data/.writecheck`，**无 `/data/media`** |
| ② 否定性结论第二手段 | `grep -rn "data/media" .github/ \| wc -l` | **0** |
| ③ 读规范自认 | 读 `0008` 冻结表逐字（行号参考 `428`，**行号易腐**）：`| #95 | G1 | 断言面 | 冻结关闭（deferred） | 内容保留在 §6 三路径 union（候选，未落地）|` | 规范文本**自认未落地** |

**结论**：`ci.yml` **实现态只探单路径 `/data`**；`0008 §6` 的三路径 union 是**规范候选、未落地**（#95 已 `deferred`）。旁证：规范 0010 §1「AC3 零触碰」段亦逐字确认 `image-guard` 的 `Assert runtime contract (non-root + /data writable)` step 现状。**本规范此后一切表述以实现态为准。**

---

## 4. 权威来源裁定（Q1）

**裁定：媒体根唯一权威来源 = 环境变量 `FLOWCART_MEDIA_ROOT`**（置信 0.90）。

**四条潜在来源逐条处置**：

| 来源 | 处置 | 理由（挂证据） |
|---|---|---|
| ① env `FLOWCART_MEDIA_ROOT` | **保留为唯一权威** | 唯一被运行的 app 实际消费的机制（`ContentAiStepProvider` 无参构造读 `System.getenv`）；生产装配**只能**经此路径取得媒体根（A9） |
| ② Spring 键 `app.media-root` | **降级为非权威声明面**（保留键 + 补标注，不物理删除） | 无消费者 ⇒ 不决定任何行为 ⇒ 非真相源；与 `app.flow.channels` 同属 §7.2 声明式配置既有定式（A2 自认「保留为声明式配置，避免与 spec 键位脱节」）；删除会连带触 `application.yml` + `AppProperties` + 两测试 + `0007 §7.2`，授权不在本票（见 §6.2 闭合论证） |
| ③ SPI 代码缺省 `target/media` | **降级为「env 未设时的 fallback」**（保留，但由接线确保生产不落到它） | 相对缺省是「未接线」的表征而非权威；接线后生产恒由 env 覆盖（A3） |
| ④ config/env 显式优先级 | **不采纳** | 双源 + 优先级 = 直接违反 #96 AC-1「不引入第二个真相源」；且 `app.media-root` 无消费者，定义「优先级」无落地意义（C5） |

**关键事实（决定裁定方向）**：不存在 config → provider 的桥——`AdapterHost.load()` 经 `ServiceLoader` 汇总 AiStep，**无 config 入参**（语义锚点：`AdapterHost.load()` / `AdapterHostConfiguration.adapterHost()`），且 `ContentWorkerFactory` 生产启动路径**不再**显式调用 `ContentAiStepProvider.of(config, mediaRoot)`（语义锚点：`ContentWorkerFactory.start(...)` 类 javadoc 段）。故 config 若要成为权威，必须**新造**这条桥（C4）。

---

## 5. 守门判据裁定（Q2）

**裁定：采纳 `0008 §10 R5`** —— 接线后守门判据由「**守承诺**（`Dockerfile` 预建 + chown 契约 + `0007 §7.2` 声明面）」**改述为「守已接线行为」；断言脚本保持不动**（置信 0.86）。

- **订正后更强结论**：实现态只有单路径 `/data`（见 §3）⇒ `/data/media` **当前根本不在守门路径集合内** ⇒ 「断言本身无需变更」**双重成立**——对**现有单路径**成立；对 `0008 §6` 候选三路径 union（若未来落地）**亦成立**（R5 已预述）。
- **本票零 CI 文件改动**（受主理人 `scope-ruling` 第 2 条约束）。round 1 拷问包曾列的 option (b)（改断言脚本）/ option (c)（把 `/data/media` 移出路径集合）均属 CI 改动面，**一并排除**。
- **不改 0008 正文**：R5 的改述为「规范态活口」，落地时按需在 0008 内改述判据文本；本票不代写（不撞车）。

---

## 6. 落地方案（文件级清单）

| # | 文件（相对路径） | 语义锚点 | 动作 |
|---|---|---|---|
| 1 | `docker-compose.yml` | `services.app` 内，`volumes:`（`app_data:/data`）之后、`depends_on:` 之前 | **新增 `environment:` 段**：`FLOWCART_MEDIA_ROOT: ${FLOWCART_MEDIA_ROOT:-/data/media}`（沿用 compose 既有 `${VAR:-default}` 纪律，同 `POSTGRES_USER` / `RABBITMQ_USER`；可用 `.env` 覆盖） |
| 2 | `app/src/main/resources/application.yml` | `app:` 节内 `media-root` 键（`role` 键之后）上方注释块 | **保留键 + 标注**：追加「⚠️ 非权威来源（v1 未由 app 代码消费）：生产媒体根权威 = env `FLOWCART_MEDIA_ROOT`（specs/0007 §7.2 / #96）；改此键不影响运行行为。」 |
| 3 | `app/src/main/java/io/autocommerce/app/AppProperties.java` | 类 javadoc「当前消费口径（如实登记）」段 | **保留绑定 + 标注**：补「`app.media-root` **非权威**，仅为 spec §7.2 声明面；权威 = env `FLOWCART_MEDIA_ROOT`」。字段 / getter / setter **保留** |
| 4 | `.env.example` | RabbitMQ 段之后、备份段之前 | **新增注释形式登记**：`# FLOWCART_MEDIA_ROOT=/data/media`（默认 `/data/media`，卷见 Dockerfile） |
| 5 | `app/src/test/java/io/autocommerce/app/AppContextTest.java` | 属性行 `app.media-root=target/app-test-media` | **不改**（无消费者 ⇒ no-op；保留即证「声明未消费」） |
| 6 | `app/src/test/java/io/autocommerce/app/AppContextWithoutWorkerRoleTest.java` | 同上 | **不改** |
| 7 | `docs/specs/0007-end-to-end-flow-assembly.md` | §7.2 YAML 块 `media-root: /data/media` | **不改键位**（保留声明面）；字面口径对齐属**非阻塞文档项（另案）** |

### 6.1 `app.media-root` 处置定档：保留并标注（降级为非权威声明面）

**不物理删除**。理由：① 与 `app.flow.channels` 同属 §7.2「声明式配置」的**本仓既有定式**（`AppProperties` 类 javadoc 自认并说明「保留为声明式配置，避免与 spec 键位脱节」）；② 物理删除会连带改 `application.yml` + `AppProperties` + 两个测试 + `0007 §7.2`，其**规范变更授权不在本票**；③ 保留声明面不破坏 R5 判据来源。

### 6.2 AC-1 闭合论证（「不引入第二个真相源」）

env 是唯一**能决定行为**的来源（唯一消费者）；`app.media-root` 无消费者 ⇒ **不决定任何行为** ⇒ 非真相源，仅是声明 ⇒ 降级标注后**未引入**任何新的竞争源 ⇒ **AC-1 满足**。

---

## 7. AC-3 可判定验证方式

**载体：本地一次性 `docker run`**（**不进 CI**，受 `scope-ruling` 第 2 条约束；亦守 #75「e2e 不进默认 CI」红线）。

```
docker run --rm -e FLOWCART_MEDIA_ROOT=/data/media --entrypoint sh flowcart/app:dev -c '
  set -e
  uid=$(id -u); echo "runtime uid = $uid"
  [ "$uid" != "0" ] || { echo "FAIL: runs as root"; exit 1; }
  root="${FLOWCART_MEDIA_ROOT:-target/media}"
  mkdir -p "$root" && echo ok > "$root/.mediawritecheck" && rm "$root/.mediawritecheck" \
    || { echo "FAIL: media root $root not writable by runtime user"; exit 1; }
  echo "PASS: media root $root writable by runtime user"'
```

- **怎么跑**：先 `docker compose build app`（或复用 CI 已构建镜像 `flowcart/app:dev`），再跑上命令；`-e` 即 compose 接线后容器内真实取值。
- **看什么**：`runtime uid`（须 ≠ 0）、`root` 解析值（须 = `/data/media`）、末行 `PASS/FAIL`。
- **什么算过**：`uid ≠ 0` **且** `root == /data/media` **且** 探针 `touch/rm` 成功（exit 0）。
- **为何可信**：探针的 `root` 由 **env 解析**得出（与 `ContentAiStepProvider` → `defaultMediaProcessor()` → `System.getenv` 同口径）。**接线缺失时 `root` 落 `target/media` ⇒ `/app/target/media` 不可建 ⇒ 探针红**。故该探针**能捕获 env 未接线**，而「只探 `/data/media`」不能（因 `Dockerfile` 预建 + chown 使 `/data/media` 恒可写，探针恒绿，不反映 env）。
- **辅助（可选、本机）**：`docker compose config | grep -A1 FLOWCART_MEDIA_ROOT`。
- **不可由既有测试替代**：`ContentWorkflowE2ETest`、`ListingFlowWorkflowE2ETest` 经**显式装配**注入 `@TempDir` 媒体根（语义锚点：二者 `mediaRoot` 字段 + `ContentAiStepProvider.of(config, mediaRoot)`），只验「给了可写根就能写」，**不覆盖**生产 env 接线路径。

---

## 8. 术语登记（Q5）

**纳入本规范**（**不改 `CONTEXT.md`**，不另立票）：

| 术语 | 定义 | 锚点 |
|---|---|---|
| **已接线写入目标** | 由 app 代码真实消费并落盘的根：`app.data-root` → `/data/flowcart` | `WorkerServiceConfiguration` → `JsonFileOrderStore` / `JsonFilePublishStateStore`（`JsonFile*Store` 内 `Files.createDirectories(file.getParent())`） |
| **已声明数据根** | 仅在配置面声明、**无 app 代码消费**的根：`app.media-root` → `/data/media`（本规范降级为「非权威声明面」） | `application.yml` → `app.media-root`；`AppProperties` javadoc 自认未消费 |

本仓「真实写入目标」一词的歧义（0008 §10 R4）即由上述二者构成；本登记为**消歧的规范性落点**。

---

## 9. 已知风险与残余风险

| 编号 | 项 | 分级 | 处置 |
|---|---|---|---|
| **R1** | **env 静默回归风险（Q4）**：`Dockerfile` 预建 `/data/media` 使该路径**恒可写**，而现实现态守门探针与 env **无关**（单路径 `/data`）⇒ 若日后从 compose 移除 `FLOWCART_MEDIA_ROOT`，app 会静默退回相对 `target/media`（`/app/target/media`，`root:root` 不可建），而 CI **仍可能全绿** | `non-blocking` | **裁定为「残余风险登记」**（本表即为落点）；**不产出任何 CI 断言新增/修改要求**（受 `scope-ruling` 第 2 条约束） |
| **R2** | `0007 §7.2` 与本规范口径的**字面一致性** | `non-blocking` | 本票**不改** 0007 正文；如需字面对齐属**非阻塞文档项（另案）** |
| **R3** | U5 接线机制：已裁 **compose `environment:`** | `non-blocking` | `Dockerfile ENV`（C3）为**保留替代**（同权威 = env，可切，见 §10） |

**blocking 未知点：0。**

---

## 10. 被拒候选（完整保留 + 拒因挂证据）

| 候选 | 一句话形态 | 拒因 | 依据 |
|---|---|---|---|
| **C1** | 把 `ContentAiStepProvider.DEFAULT_MEDIA_ROOT` 由相对 `target/media` 改绝对 `/data/media` | ① **AC-1 未闭**（`app.media-root` 仍声明未消费）；② 把容器部署布局**硬编码进 content 业务模块**，违 ADR-0007 插件化；③ 本地 dev 默认写 `/data/media` 可能无权限/污染宿主 | `ContentAiStepProvider` 常量；ADR-0007 |
| **C3** | `Dockerfile` runtime 段加 `ENV FLOWCART_MEDIA_ROOT=/data/media` | ① env 定义点落**镜像层**，与 compose 既有 `${VAR:-default}` 纪律分处两层 ⇒ 重复/漂移风险；② 部署路径烧进镜像层，违「配置外置」；③ 仍须处置 `app.media-root`。**保留为 C2 的非阻塞替代（同权威 env，可切）** | `Dockerfile` runtime 段；`docker-compose.yml` `${VAR:-default}` 定式 |
| **C4** | config `app.media-root` 权威 + 桥接进 provider | ① **推翻 A9**（生产特意移除 `of(config, mediaRoot)`）；② 需**新造**一条 `AdapterHost.load()` 缺失的 config 入参桥（E1′ 证明不存在）；③ 动装配契约与 SPI 语义，改造面最大；④ 与 ADR-0007 张力最大 | `ContentWorkerFactory` javadoc；`AdapterHost.load()`；`AdapterHostConfiguration` |
| **C5** | 双源 + 显式优先级（env 覆盖 config 或缺则回退） | **直接违反** #96 AC-1「不引入第二个真相源」；且 `app.media-root` 无消费者，定义「优先级」无落地意义 | AC-1；`AppProperties` javadoc |

**评分矩阵**（权重：AC-1 0.25／AC-2 0.15／AC-3 0.20／改造面风险 0.20／哲学一致 0.20）：**C2 4.80（选定）** > C3 4.15 > C1 2.75 > C4 1.95 > C5 1.75；敏感度检验三组权重下**排序稳健**。

---

## 11. AC 映射 + triage 裁定 + build 票范围

**AC 逐条映射**：

- AC-1（triage 完成）→ **裁定「需先出 spec」** ✅
- AC-2（权威来源确定且不引入第二真相源）→ §4（env 唯一权威 + `app.media-root` 降级）+ §6.2 闭合论证 ✅
- AC-3（守门判据）→ §5（判据改述、断言不动、**零 CI 改动**）✅
- AC-4（容器内可写 + 可判定验证）→ §7（本地一次性 env-解析探针）✅

**triage 裁定**：**「需先出 spec」**。理由：① 含配置面处置决策（保留/删除/降级）+ `0007 §7.2` 声明面边界，属设计决策非纯实现；② 含规范层级裁定（守门判据改述、术语登记）；③ 本票为 `needs-triage` 问题登记票，本仓纪律「先 Spec 后 Build」。

**build 票范围**（spec 定稿后）：§6 落地方案 **#1–#4** 四项文件改动 + §7 AC-3 验证。**不含**任何 CI 文件改动、不含 `0007` 正文改动、不含 `CONTEXT.md` 改动。

---

## 12. 可否决声明（6 条 + 回滚路径）

1. 权威来源 = env `FLOWCART_MEDIA_ROOT`；
2. `app.media-root` = **保留并标注**（不物理删除）；
3. `0007 §7.2` = **本票不改键位**；
4. 守门 = **判据改述、断言不动**（零 CI 改动）；
5. AC-3 载体 = **本地一次性 env-解析探针**（不进 CI）；
6. 五条件第 4 条「是否命中安全」= **唯一翻盘点**（若真人把「为媒体落盘位置定权威」认定为安全类裁定 ⇒ 本条翻盘 ⇒ `need_human=true`；判定权在真人）。

**回滚路径**：删 `docker-compose.yml` 中 `app` 服务的 `environment:` 块 + 撤销 #2/#3/#4 三处标注 ⇒ **零语义残留**，并**重出 DecisionRecord**。

---

## 13. 证据台账

| 证据 ID | 引用（语义锚点 / 文件·行） | 原文摘录（≤80 字） | 证明什么 | 强度 |
|---|---|---|---|---|
| A1 | `application.yml` → `app:` → `media-root` 键（行参考 `36`，**易腐**） | `media-root: /data/media` | app 声明媒体根（未接线） | 高（一手，复核✓） |
| A2 | `AppProperties` 类 javadoc「当前消费口径」（行参考 `22-27`，**易腐**） | `app.media-root`…v1 尚未由 app 代码读取；媒体根由 SPI provider 从 env 取 | 声明未消费（自认） | 高（复核✓） |
| A3 | `ContentAiStepProvider`：`ENV_MEDIA_ROOT` / `DEFAULT_MEDIA_ROOT` / 无参构造（行参考 `39,41,84-86`，**易腐**） | `ENV_MEDIA_ROOT="FLOWCART_MEDIA_ROOT"`；`DEFAULT_MEDIA_ROOT="target/media"`；无参构造读 `System.getenv` | 生产唯一读取机制 + 相对缺省 | 高（复核✓） |
| A4 | `docker-compose.yml` → `services.app` | app 服务无 `environment:` 段（下一处在 temporal-ui 服务） | env 未接线 | 高（复核✓） |
| A5 | `Dockerfile` runtime 段 `RUN mkdir … chown -R`（行参考 `48`，**易腐**） | `RUN mkdir -p /data/media /data/flowcart && chown -R flowcart:flowcart /data` | 预建 + chown 契约 | 高（复核✓） |
| A6 | `Dockerfile` `WORKDIR` / `USER`（行参考 `50,58`，**易腐**） | `WORKDIR /app`；`USER flowcart` | 非 root 运行 + 工作目录 | 高（复核✓） |
| A7 | `docker-compose.yml` → `services.app` → `volumes`（行参考 `147-150`，**易腐**） | `app_data:/data` 命名卷 | 持久化 | 高（复核✓） |
| A8 | `WorkerServiceConfiguration` → `publishStateStore` / `orderStore`（行参考 `57-65`，**易腐**） | `app.data-root` → `JsonFilePublishStateStore` / `JsonFileOrderStore` | data-root 已接线（唯一落盘根） | 高（复核✓） |
| A9 | `ContentWorkerFactory.start(...)` 类 javadoc（行参考 `23`，**易腐**） | 生产不再调 `ContentAiStepProvider.of(config, mediaRoot)` | 生产仅经 ServiceLoader+env | 高（复核✓） |
| E1′ | `AdapterHost.load()` + `AdapterHostConfiguration.adapterHost()` | `AdapterHost.load()` 经 `ServiceLoader` 汇总 AiStep，**无 config 入参** | 不存在 config→provider 桥 | 高（一手） |
| E2′ | `ArchiveMediaProcessor.process` → `Files.createDirectories(root)` | `Files.createDirectories(root)` 后写文件 | 失败点：相对根 `/app/target/media` 不可建 | 高（一手） |
| E3′ | `.env.example`（全文无 `FLOWCART_MEDIA_ROOT`） | env 未在任何模板登记 | 接线缺口补充证据 | 高（一手） |
| E4′ | `AppContextTest` / `AppContextWithoutWorkerRoleTest` 属性行 | 测试设 `app.media-root=target/app-test-media`，无消费者 | 佐证「声明未消费」 | 中（文件一手 + 无消费为推断） |
| E5′ | `CONTEXT.md`（全文） | 无「媒体根」「真实写入目标」词条 | R4 场景成立 | 高（一手） |
| E6′ | `content/src/main/resources/META-INF/services/io.autocommerce.core.step.AiStepProvider` | `io.autocommerce.content.spi.ContentAiStepProvider` | SPI 发现路径 | 高（一手） |
| E7′ | `0008 §4.3` V8/V9/V13 | 合法镜像三路径绿；腐化必红；`/app/target/media` `Permission denied` | 实测基线 | **中**（**他人实测，未我方复现**；注：V8/V9 涉及「三路径 union」为**候选态**实测，非实现态） |
| **R-C1** | `.github/workflows/ci.yml` `Assert runtime contract (non-root + /data writable)` step（行参考 `125-132`，**易腐**） | 脚本仅 `touch /data/.writecheck && rm /data/.writecheck`；**无 `/data/media`** | 实现态 = 单路径 `/data` | 高（主理人复算，一手） |
| **R-C2** | `grep -rn "data/media" .github/ \| wc -l` | 输出 `0` | 否定性第二手段 | 高（主理人复算） |
| **R-C3** | `0008` 冻结表 `#95` 行（行参考 `428`，**易腐**） | `内容保留在 §6 三路径 union（候选，未落地）` | 规范文本自认未落地 | 高（主理人复算，一手） |
| **R-C4** | `0010 §1`「AC3 零触碰」段 | `不改 ci.yml 的 changes job 判据与 image-guard 的 Assert runtime contract … step 一字` | 旁证 step 现状 = 单路径 | 高（一手） |
| R1 | `0008 §5.5` / `§10 R5` | 「系守预建契约…非守已接线行为」；R5：接线后判据改述、断言无需变更 | 守门判据来源与改述活口 | 高（复核✓） |
| R2 | `0008 §10 R3` | 「另立票修接线（`Dockerfile` ENV 或 compose environment）」 | 本票缺陷登记 | 高（复核✓） |
| R3 | `0008 §10 R4` | 「术语歧义…建议在 `CONTEXT.md` 区分」 | §8 术语登记来源 | 高（复核✓） |
| R4 | `0007 §7.2` YAML 块 `media-root: /data/media`（行参考 `265`，**易腐**） | 声明面 | 声明面 | 高（复核✓） |
| R5 | `0009 §13`（行参考 `252`，**易腐**） | 「…另立票；本规范不触碰」 | 越界票登记 | 高（复核✓） |

**复核结论**：round 1 拷问包 A1–A9 **全部相符，无订正**；**唯一订正** = §3「三路径 union 属规范候选未落地」（三通道复算成立）。E7′ 保持「他人实测未复现·强度中」。

---

## 14. 文件级修改清单（**语义锚定为主；行号仅参考且「行号易腐」**）

| # | 文件（相对路径） | 动作 | 语义锚点 |
|---|---|---|---|
| 1 | `docker-compose.yml` | **改**（新增 `environment:` 段） | `services.app` → `volumes`（`app_data:/data`）之后 / `depends_on` 之前 |
| 2 | `app/src/main/resources/application.yml` | **改**（追加注释） | `app:` 节 → `media-root` 键上方注释块 |
| 3 | `app/src/main/java/io/autocommerce/app/AppProperties.java` | **改**（javadoc 追加标注） | 类 javadoc「当前消费口径（如实登记）」段 |
| 4 | `.env.example` | **改**（新增注释行） | RabbitMQ 段之后 / 备份段之前 |
| 5 | `app/src/test/java/io/autocommerce/app/AppContextTest.java` | **不改** | 属性行 `app.media-root=…` |
| 6 | `app/src/test/java/io/autocommerce/app/AppContextWithoutWorkerRoleTest.java` | **不改** | 同上 |
| 7 | `docs/specs/0007-end-to-end-flow-assembly.md` | **不改** | §7.2 YAML 块 `media-root: /data/media` |

**数据清理范围**：无。**表结构变更与迁移脚本**：无。**CI 文件**：**零改动**。**`CONTEXT.md`**：**零改动**。

---

## 15. 落库要素（已落库于 issue #128；本节保留为登记）

**spec 票标题**：
`Spec: 媒体根权威来源与接线（env 唯一权威 + compose 接线）`

**body 摘要**：
> 承接 #96「媒体根未接线」。本 spec 裁定：① 媒体根**唯一权威来源 = env `FLOWCART_MEDIA_ROOT`**；`app.media-root` 降级为非权威声明面（保留并标注）；SPI 代码缺省 `target/media` 降级为 fallback。② 守门判据由「守承诺」**改述为「守已接线行为」**，断言脚本保持不动（**零 CI 改动**）。③ AC-3 验证 = 本地一次性 `docker run` 的 **env-解析写入探针**（不进 CI）。④ 术语登记：已接线写入目标（`/data/flowcart`）vs 已声明数据根（`/data/media`）。
> 订正：`0008 §6` 三路径 union 为**规范候选未落地**；`ci.yml` 实现态只探单路径 `/data`。
> 落地面：`docker-compose.yml`（`services.app` 加 `environment:`）+ `application.yml` / `AppProperties` / `.env.example` 三处标注；`0007` 与 `CONTEXT.md` 不动。
> 决策记录：`DR-FLOWCART-0096-01`；可否决点：五条件第 4 条「是否命中安全」。

---

## 附：明确不做的事（范围外）

| 项 | 归属 |
|---|---|
| 任何 CI 断言的新增 / 修改（含 `0008 §6` 三路径 union 的落地） | 不在本票（`scope-ruling` 第 2 条）；#95 已 `deferred` |
| `CONTEXT.md` 术语表改动 | 不在本票（`scope-ruling` 第 1 条）；术语登记只落本规范 §8 |
| `0007 §7.2` 正文键位改动 | 本票不改；字面对齐为非阻塞文档项（另案） |
| `app.media-root` 物理删除 | 本票不删（§6.1） |
| #75 e2e 编排 / `docker compose up -d` | #75 红线 |
| 镜像内容架构重写 | 后续 |
