# 规范：CI 镜像构建守门（build + 轻量运行时断言）

> 来源：Grilling session（2026-09-23），议题「CI 应以何种形态为镜像构建加守门」（GitHub issue [#86](https://github.com/luochenfx/ecom-flowcart/issues/86)）
> 依赖：[规范 0007（端到端链路打通）](./0007-end-to-end-flow-assembly.md)（`Dockerfile` 与 compose `app` 服务的来源）、issue [#74](https://github.com/luochenfx/ecom-flowcart/issues/74)（PR #85 终审遗留登记）、issue [#75](https://github.com/luochenfx/ecom-flowcart/issues/75)（e2e 验收）
> 修订来源（v2）：Grilling session（2026-09-24），议题「镜像守门的运行时契约断言应以何种路径集合 + 何种判定口径覆盖容器内真实写入目标」（GitHub issue [#92](https://github.com/luochenfx/ecom-flowcart/issues/92)，源出 PR #91（#89）独立审查 `ci.yml:124` 的 [Nit]）；DecisionRecord `DR-FLOWCART-0092-01`（**已获真人授权**）。**§6 断言口径已由「`/data` 顶层单探」修订为「三路径 union（`/data` + `/data/media` + `/data/flowcart`）+ 结构锚定 + 纯可写性 + 失败逐路径可定位」**（见 §1「入口与断言」、§5.4、§6、§7）。
> 状态：**v2（#92 修订）** —— 基线为 v1 设计期决议（**已获真人授权**），本次为对**同一断言口径的修正**（非新命题，故就地修订、不另立新规范）；实现另立 build 票，**本规范不改任何实现文件**。
> 范围声明：本规范**只解决「镜像构建这一环节的 CI 守门形态」**。以下明确**不在本规范范围**（见 §13）：#75 e2e 作业编排、镜像内容架构重写、CI runner 成本治理、销售/订单侧生产 Adapter。

## 1. 决策概览

- **采纳 C2：build + 轻量运行时断言**——`docker compose build app` 冒烟通过后，用一次性容器直验 `Dockerfile` 承载的运行时契约（非 root + `/data` 可写）。评分矩阵 **C2 4.60 > C3 3.30 > C1 3.13 > C0 2.80**（7 准则加权，权重和 1.00，含权重敏感度检验「排序稳健」）。**（v2 / #92 后：轻断言的口径为「非 root + 三路径可写」，见本节「入口与断言」与 §5.4）**
- **守门语义深度 = (b)**：构建成功 + 镜像运行时轻断言（不止 `exit=0`）。
- **门禁位置与触发 = (i)+(v)**：纳入 **PR 门禁**，且以 **paths 白名单**限定触发面（仅镜像相关路径变更触发）。
- **job 落点结构 = (B)**：**在既有 `ci.yml` 内新增独立 job**，**不新开第二个 workflow**。
- **(i)+(v) 的忠实实现方式 = job 级白名单闸门**：因 GHA 规定 `paths` 与 `paths-ignore` **不可在同一 event 并用**，且 **workflow 级** `paths` 会连带限缩既有 `Build & Test (JDK 21)` 的 PR 覆盖，故**不用** workflow 级 `on.pull_request.paths`，改为「轻量 `changes` 闸门 job（`dorny/paths-filter@v3`）+ `image-guard` 以 `needs.changes.outputs.image` 为 `if`」（见 §6）。既保住白名单语义，又不新开 workflow、不缩小既有 job 覆盖。
- **入口与断言**：入口 = `docker compose build app`（与部署入口同源，含 compose 解析）；镜像 tag = `flowcart/app:dev`（`docker-compose.yml:143`）；断言（**v2 / #92 修订后口径**）= 运行用户 `id -u ≠ 0` **且** `/data`、`/data/media`、`/data/flowcart` **三处**的 `.writecheck` 均可创建可删除（**结构锚定**：子目录缺失即红；**纯可写性**：不加属主 / 权限位断言；失败信息逐路径可定位）。
- **刻意不进 #75 地盘**：**不跑 `docker compose up -d`**——`app` 依赖 postgres / rabbitmq / temporal-init 就绪链，起全栈即侵入 #75 的 e2e 编排边界（见 §5.3 C3 被拒理由）。
- **「CI 绿」口径变更（已获真人授权）**：由「`ci.yml` + `qodana`」→「`ci.yml`（含 `image-guard`）+ `qodana`」，即 `build` / `image-guard` / `qodana` 三者皆绿。
- **零契约变更**：本规范不触碰 `core-contracts` 的字段 / 方法签名，不触发 `AGENTS.md` 的「core 改动即中止」闸门（见 §11）。

## 2. 来源与关系（红线）

| 载体 | 与本规范的关系 | 红线 |
|---|---|---|
| **#86**（本规范命题载体） | 登记「CI 从不构建 Docker 镜像 → `Dockerfile` 的容器承诺零自动化守门」；proposal 自问「是否纳入 PR 门禁需评估耗时」 | —— |
| **#74 / PR #85**（来源） | 其终审登记遗留项即 #86；`Dockerfile` 的 `/data` 创建与属主设置、compose 的 `app_data` 卷均出自该 PR | 该 PR 的改动**未被任何 CI 步骤覆盖**，正是本规范要消除的空白 |
| **#75**（Related，e2e 验收） | 同为「验证 Docker 产物」的相邻票，**但边界必须切干净** | **#75 的 AC 已写死「e2e 不进默认 CI」**（`gh issue view 75` 实测）。本规范的 `image-guard` **不得**成为 e2e、**不得**跑 `docker compose up -d`、**不得**启动 postgres/rabbitmq/temporal 任一容器 |

**边界一句话**：#86 守门的是**镜像构建产物本身**（build 能否成功 + 运行时契约是否成立）；#75 守门的是**全链路能否跑通**（需起全栈）。二者以「是否 `compose up -d`」为分界，**本规范严格停在界内**。

## 3. 命题与范围

### 3.1 命题（in）

| 维度 | 内容 |
|---|---|
| 命题 | CI 应以哪种形式为镜像构建加守门（含是否/如何纳入 PR 门禁），使 `Dockerfile` 承载的「非 root `flowcart` + `/data` 开箱可写可持久化」承诺不再**零自动化守门** |
| In | CI 守门**形态**（build 命令与断言口径、触发条件、耗时预算、与既有 job / step 的关系）+ 是否 / 如何纳入 PR 门禁 |
| Out | #75 e2e 作业编排；镜像内容架构重写（多阶段结构、层序优化）；CI runner 成本治理；销售 / 订单侧生产 Adapter（#74 已知缺口） |

### 3.2 收敛状态

- blocking 未知点 **0**；候选集**稳定**（C0 / C1 / C2 / C3 已覆盖守门深度完整谱系，无中间形态缺口）；`need_human = false`（真人已显式知情承接）；**综合置信度 0.85**。
- **（v2 / #92 修订）** 本节为 **v1 原命题（守门深度）的收敛记录**，原样保留；**本次「断言路径集合与判定口径」修订的收敛状态见 §10 尾部**（blocking 未知点 0、综合置信度 **0.86**、残余 `R1`/`R2` 均为 `non-blocking`）。

## 4. 现状与证据

### 4.1 守门空白现状

`ci.yml` 现有三步与镜像的关系（`.github/workflows/ci.yml:48-56`）：

| 现有 step | 与镜像的关系 |
|---|---|
| `Validate bootstrap scripts syntax`（`sh -n …`） | 与镜像无关 |
| `Validate docker-compose syntax`（`docker compose config --quiet`，`ci.yml:51-52`） | **仅解析 compose 语法**——不解析 `Dockerfile`、不构建任何镜像 |
| `Build & test (mvn clean test)`（`ci.yml:54-56`） | 只跑 Java 测试，**不碰镜像** |

因此「`RUN mkdir/chown` 层序错位、`COPY --chown` 失效、基础镜像或构建插件不可用」这类腐化，CI 一律看不见——即本规范要消除的空白。

### 4.2 证据台账

| 编号 | 引用 | 得到的结论 | 可信度 |
|---|---|---|---|
| E1 | `.github/workflows/ci.yml:54-56`（实测） | CI 现状只跑 `mvn clean test` | 高（一手文件） |
| E2 | `.github/workflows/ci.yml:51-52`（实测） | compose 仅 `config --quiet` 语法校验 | 高 |
| E3′ | `Dockerfile:1,24` + E16–E22 | `Dockerfile` 依赖 BuildKit（`# syntax=docker/dockerfile:1`、`RUN --mount=type=cache`）⇒ 在 `ubuntu-latest` 的 `docker compose build` 下**默认满足** | 高（文件 + 官方文档 + 机制反证） |
| E4 | `Dockerfile:38,45,50-53,55`（实测） | 「非 root + `/data`」承诺只在 runtime 段引入（`useradd` / `mkdir -p /data/media /data/flowcart && chown -R` / `COPY --chown` / `USER flowcart`） | 高 |
| E5 | 全仓推断 | 「build 成功不校验 `USER` / `chown`」——**推断**（缺构造性反例，如实降级） | 中（推断，非实测） |
| E6 | `docker-compose.yml:136-157`（实测） | `app` 服务 build / image / 卷 / 依赖拓扑：`build: context=.`、`image: flowcart/app:dev`、`app_data:/data`、`depends_on` postgres/rabbitmq/temporal-init | 高 |
| E7 | `gh issue view 75`（实测） | #75 AC 写死「e2e 不进默认 CI」 | 高 |
| E8 | `gh issue view 86`（实测） | #86 proposal 自问 PR 门禁与耗时；实测本机 `docker compose build app` exit=0、~50s（**未一手复现**） | 中（E9 未复现） |
| E9 | #86 原文 | 原生 Windows `docker compose build app` exit=0、~50s | **未一手复现** |
| E10 | `.github/workflows/ci.yml:31-35,58-65`（实测） | 既有单 job（`Build & Test (JDK 21)`）、`timeout-minutes: 20`、失败上传 artifact | 高 |
| E11 | `.github/workflows/qodana_code_quality.yml`（实测）+ #74 终审 | 存在独立 `qodana` workflow ⇒ 现状「CI 绿」= `ci.yml` + `qodana` 二者皆 pass | 高 |
| E13 | `.dockerignore:17-23` | 构建上下文排除项（`docs`、`schemas`、`CONTEXT.md`、`AGENTS.md` 等） | 高 |
| E16–E20 | `actions/runner-images` Ubuntu2404 README（2026-09-23 查询快照） | runner = Ubuntu 24.04（image `20260907.300.1`）、Docker Client/Server **28.0.4**、Compose **2.38.2**、Buildx **0.37.0** | 中（**查询日快照**，见 U3） |
| E21 | Docker 官方 Compose V2 GA 文档 | Compose v2 的 `build` **默认走 BuildKit**；opt-out 唯一手段 `DOCKER_BUILDKIT=0` | 高（官方文档） |
| E22 | 机制反证 | classic builder 不支持 `RUN --mount=type=cache`；若 BuildKit 未生效会**显式失败**，不会静默降级 | 高 |
| E23 | `gh run list --workflow=ci.yml --limit 10`（本仓一手实测） | `Build & Test (JDK 21)` 耗时 **1m35s–4m51s**（push/main 3m29s–3m49s；PR 1m35s–4m51s）；Qodana 2m10s–2m25s | 高 |

### 4.3 （v2 / #92 修订）证据台账与腐化谱系实测

**取证环境（一手）**：本机 Docker 29.7.2；被测镜像 = 本仓 `flowcart/app:dev`（runtime 基镜像 `eclipse-temurin:21-jre`）。**腐化镜像经 `docker run -u 0` 改属主 / 权限后 `docker commit --change 'USER flowcart'` 派生**——未改仓库任何文件、未重跑 Maven 构建，故属**等价构造**（与 `Dockerfile:48` 去 `-R` 的最终文件系统状态一致）。取证过程未落盘任何临时文件。

| 编号 | 引用 / 命令 | 得到的结论 | 可信度 |
|---|---|---|---|
| V1 | `Dockerfile:48` | `RUN mkdir -p /data/media /data/flowcart && chown -R flowcart:flowcart /data`——腐化点与预建契约 | 高（一手文件） |
| V2 | `app/src/main/resources/application.yml:36,38` | `app.media-root: /data/media`、`app.data-root: /data/flowcart` | 高（一手文件） |
| V3 | `app/src/main/java/io/autocommerce/app/AppProperties.java:22-26` | **显式登记** `app.media-root` 未被 app 代码消费；媒体根由 SPI provider 从 env 取 | 高（一手文件） |
| V4 | `app/src/main/java/io/autocommerce/app/WorkerServiceConfiguration.java:57-65` | `app.data-root` → `JsonFilePublishStateStore` / `JsonFileOrderStore`（**唯一已接线落盘根**） | 高（一手文件） |
| V5 | `order/.../JsonFileOrderStore.java:44,129-136`、`publish/.../JsonFilePublishStateStore.java:50,94-105` | 写 `{root}/orders.json` / `{root}/publish-states.json`；写前均 `Files.createDirectories(file.getParent())`（**惰性自建**） | 高（一手文件） |
| V6 | `content/.../spi/ContentAiStepProvider.java:39,41,84-85` | `ENV_MEDIA_ROOT = FLOWCART_MEDIA_ROOT`；缺省 `target/media`（**相对路径**） | 高（一手文件） |
| V7 | `docker-compose.yml:136-162`（`app` 服务） | **无 `environment` 段**（下一处 `environment:` 在 `:169`）⇒ `FLOWCART_MEDIA_ROOT` 未接线 | 高（一手文件） |
| V8 | `docker run --rm --entrypoint sh flowcart/app:dev -c '…'` | 合法镜像：`uid=999`（`flowcart`）；`/data`、`/data/media`、`/data/flowcart` 均 `flowcart:flowcart 755`；**三处探针全绿** | 高（一手实测） |
| V9 | 腐化镜像（V1 去 `-R` 等价态） | **#92 反例一手复现**：现探针 `/data` 顶层 **`exit 0` 绿**，而 `/data/media`、`/data/flowcart` 写入均 **`Permission denied`** | 高（一手实测） |
| V10 | 腐化谱系逐例实测 | 见下表（11 行） | 高（一手实测）／标注项为推断 |
| V11 | `docker run -v probe_ro:/data:ro …` | `/data` 只读挂载 ⇒ 现探针**红**（`Read-only file system`） | 高（一手实测） |
| V12 | 容器内 `command -v` | `sh` / `bash` / `stat` / `find` / `touch` / `rm` / `id` / `mkdir` **均存在**（`/usr/bin/*`）——断言可用的工具面 | 高（一手实测） |
| V13 | `mkdir -p target/media`（CWD=`/app`） | 运行用户 **`Permission denied`**（`/app` 属 `root:root 755`）⇒ 媒体根**缺省路径不可建** | 高（一手实测） |
| V14 | 全仓 grep（`app` / `content` / `order` / `publish` / `catalog` / `worker-runtime` / `api` / `projection` / `adapter-host` 的 `src/main`：`createTempFile` / `FileOutputStream` / `java.io.tmpdir` / `logging.file` 均 0 命中） | **未见**其他运行时落盘目标（日志走 stdout、无 actuator 落盘、无租户目录） | 中（**推断**，grep 覆盖 main 源集，非穷尽） |

**腐化谱系实测表**（对象 = 运行用户可写性；「现探针」= v1 的 `/data` 顶层单探）

| # | 腐化构造 | 现探针（`/data` 顶层） | 子目录探针（v2 断言） | 实测编号 |
|---|---|---|---|---|
| 1 | `/data` 属主错（`root:root`） | **红** | 红 | V10-1 |
| 2 | `/data` 权限位缺 `w` | 红 | 红 | 推断（同 #1 机制） |
| 3 | `/data` 不可穿越（`chmod 000`） | **红** | 红 | V10-3 |
| 4 | **子目录属主错（#92 反例：`chown` 去 `-R`）** | **绿**（**漏**） | **红** | V9 |
| 5 | 子目录权限位缺 `w`（`chmod 555 /data/flowcart`） | **绿**（漏） | **红** | V10-5 |
| 6 | 子目录不可穿越 | 绿（漏） | 红 | 推断（同 #5 机制） |
| 7 | `/data` 只读挂载 | **红** | 红 | V11 |
| 8 | `/data` 的父目录不可穿越 | 红 | 红 | 推断（`/` 不可改，未实测） |
| 9 | 子目录为符号链接指向不可写目标 | **绿**（漏） | **红** | V10-9 |
| 10 | 子目录缺失 | 绿 | 红（裸 `touch`）／**绿**（`mkdir -p` 变体） | V10-10 |
| 11 | 子目录 `root:root` 但 `0777`（**功能可用**） | 绿 | 绿（**非腐化**——故不加属主断言，见 §5.4 C3） | 推断 |

**结论**：现口径漏 #4 / #5 / #6 / #9（子目录一侧全面漏检），#92 反例属其中 #4；v2 三路径探针在腐化镜像上**必红**（V9/V10）、在合法镜像上**必绿**（V8），满足本命题判据。

## 5. 决策结论与被拒候选

### 5.1 选定形态：C2（build + 轻量运行时断言）

| 准则 | 结论 |
|---|---|
| 与命题贴合 | 直接覆盖 #86 的核心失败模式（构建腐化 + 运行时契约腐化） |
| 与既有 job 关系 | 新增**独立 job**，失败只影响该 check，**不动** `Build & Test (JDK 21)` 的触发与覆盖（E10/E11） |
| 触发面 | PR 门禁 + paths 白名单（job 级闸门，见 §6） |
| 耗时预算 | 新增 job 目标落于 E23 基线（1m35s–4m51s）的可接受增量内（一次冷 Maven `package` 量级）；`timeout-minutes: 20` 兜底 |
| 可观测性 | 失败上传 artifact + `docker image inspect` 现场取证 |
| 可逆性 | 删除两个 job 块即可，零语义残留 |

### 5.2 评分矩阵（7 准则加权，权重和 1.00）

| 候选 | 得分 | 排序 |
|---|---|---|
| **C2**（build + 轻量运行时断言） | **4.60** | 1（选定） |
| C3（build + 全栈冒烟） | 3.30 | 2 |
| C1（build-only） | 3.13 | 3 |
| C0（现状基线） | 2.80 | 4 |

含权重敏感性检验，结论「**排序稳健**」。

### 5.3 被拒候选（保留理由与依据）

| 候选 | 一句话形态 | 拒因 | 依据 |
|---|---|---|---|
| **C3** | `docker compose up -d` 起全栈后冒烟断言 | **越界 + 成本不可控**：其 `up -d` 与 #75 的启动前置动作完全重合（`app` 依赖 postgres / rabbitmq / temporal 就绪链），断言内容多少**不改变「已侵入 #75 编排地盘」的性质**；成本分钟级，PR 门禁不可行 | E6（依赖拓扑）、E7（#75 AC「e2e 不进默认 CI」） |
| **C1** | 只构建并断言 `exit=0` | **抓不住 #86 核心失败模式**：对「层序错位 / `COPY --chown` 失效但构建仍绿」的**静默腐化**零覆盖 | E4、E5 |
| **C0** | 现状（仅 `docker compose config --quiet`） | **即问题本身**：不解析 `Dockerfile`、不构建 | E1、E2 |

### 5.4 （v2 / #92 修订）断言路径集合与判定口径：候选集、评分矩阵与被拒候选

> **标签空间提示（防误读）**：§5.1–§5.3 的 `C0`–`C3` 属 v1 的「**守门深度**」谱系（build-only / build+轻断言 / 全栈冒烟）；本节的 `C0`–`C4` 属 v2 的「**断言路径集合与判定口径**」谱系。**二者同名不同义**，请不要跨节比对编号。本节候选是对 §6 断言口径（原「`/data` 顶层单探」）的修订选项。

选定 = **C1**（三路径 union 可写探针：`/data` + `/data/media` + `/data/flowcart`；结构锚定 naive `touch && rm`；纯可写性、不加属主 / 权限位断言；失败信息逐路径可定位；内联于 `ci.yml`）。

| 编号 | 一句话形态 | 判决 |
|---|---|---|
| **C1（选定）** | `for p in /data /data/media /data/flowcart; do touch "$p/.writecheck" && rm "$p/.writecheck" \|\| { echo "FAIL: $p not writable by runtime user"; exit 1; }; done` | 采纳 |
| C0 | 现状基线：只探 `/data` 顶层 | 拒 |
| C2 | 顶层 + `/data/flowcart`（排除未接线的 `/data/media`） | 拒 |
| C3 | C1 + 属主 / 权限位硬断言（`stat` == `999:999 755`） | 拒 |
| C4 | 遍历式：`find /data -type d` 逐目录探 | 拒 |
| C5 | 端到端实调应用（起容器跑 jar 触发真实落库后校验） | 拒（范围外，见 §2 红线 / §13） |

**评分矩阵（6 准则，权重和 1.00）**

| 准则 | 权重 | C0 | C1 | C2 | C3 | C4 |
|---|---|---|---|---|---|---|
| 目标达成度 | 0.30 | 2 | **5** | 4 | 3 | 4 |
| 约束符合度 | 0.15 | 5 | **5** | 5 | 4 | 4 |
| 成本 | 0.15 | 5 | **5** | 5 | 5 | 5 |
| 风险 | 0.20 | 1 | **4** | 4 | 2 | 2 |
| 可逆性 | 0.10 | 5 | **5** | 5 | 5 | 5 |
| 可验证性 | 0.10 | 4 | **5** | 4 | 3 | 3 |
| **加权得分** | 1.00 | 3.20 | **4.80** | 4.40 | 3.45 | 3.75 |
| **排序** | | 5 | **1（选定）** | 2 | 4 | 3 |

敏感度检验：均权 C1 4.83 > C2 4.50 > C4 3.83 > C0/C3 3.67；偏风险 C1 4.65 > C2 4.25 > C4 3.35 > C3 3.05 > C0 2.60；偏成本 C1 4.90 > C2 4.60 > C4 4.15 > C3 3.95 > C0 3.90 ⇒ **排序稳健**。

**被拒候选与拒因（保留）**

| 候选 | 一句话形态 | 拒因 | 依据 |
|---|---|---|---|
| **C0** 现状基线 | 只探 `/data` 顶层（`ci.yml:118-125`） | **漏子目录腐化**：#92 反例（`chown` 去 `-R`）下现探针 `exit 0` 绿（`GREEN-LEAK`），**已一手复现** | 实测：腐化镜像上顶层探针绿、`/data/media`、`/data/flowcart` 写入均 `Permission denied`（§4.3 V9 / V10） |
| **C2** 顶层 + `/data/flowcart` | 排除「声明未接线」的 `/data/media` | **`/data/media` 腐化会绿**，违反「腐化必红」判据；`Dockerfile:48` 已把 `/data/media` 写成 `flowcart` 属主的预建契约、规范 0007 §7.2 已声明该配置面 | `Dockerfile:48`、`0007-end-to-end-flow-assembly.md` §7.2 |
| **C3** C1 + 属主 / 权限位硬断言 | 追加 `stat -c %u:%g %a` 必须 == `999:999 755` | **假红**：`/data/flowcart` 为 `root:root 0777` 时对运行用户**可写可用**（功能正确），属主断言却判红，违反「合法镜像必绿」；且硬编码 `999:999 755` 与基镜像 uid/gid、umask 耦合，脆弱 | 实测：`0777` 目录下 `touch && rm` 成功（功能可用） |
| **C4** 遍历式探针 | `find /data -type d` 逐目录探 | **越范围 + 假红面**：为「未来假想需求」（如租户隔离子目录）预留机制；未来任一合法只读子目录即假红；`find` 非 POSIX 保证（本镜像可用属实现细节，非契约） | v2 取证实测（本镜像 `find` 存在，但不构成 POSIX 保证） |
| **C5** 端到端实调应用 | 起容器跑 jar 触发真实落库后校验文件 | **越界**：属 #75 e2e / `docker compose up -d`，触 §2 红线；成本分钟级且需 postgres / rabbitmq / temporal 就绪链 | §2 红线、§13 |

### 5.5 （v2 / #92 修订）路径集合的判据来源：`/data/media` 属「已声明数据根」，非「已接线写入目标」

本修订**必须**连带登记一项一手事实（它决定了 C2 为何仍须被拒、C1 为何仍取三路径）：

- **已接线写入目标** = `/data/flowcart`：`app.data-root` → `WorkerServiceConfiguration`（`JsonFileOrderStore` 写 `{root}/orders.json`、`JsonFilePublishStateStore` 写 `{root}/publish-states.json`），**有代码真写**。
- **已声明数据根** = `/data/media`：`app/src/main/resources/application.yml` 声明 `app.media-root: /data/media`，但 `AppProperties` 的 javadoc **显式登记该键 v1 未被 app 代码消费**；生产媒体根由内容 SPI provider 从**环境变量** `FLOWCART_MEDIA_ROOT` 取（缺省 `target/media`），而 compose 的 `app` 服务**未设置该环境变量** ⇒ 容器内媒体根落 `/app/target/media`，`/app` 属 `root:root` ⇒ 运行用户实测不可创建。
- **结论**：C1 纳入 `/data/media` **系守 `Dockerfile:48` 的预建契约与规范 0007 §7.2 的声明面**（守承诺），**不是**「守已接线行为」。此口径差异已在 §10 以 `assumed` 体例登记；媒体根未接线本身属**配置接线缺陷**，**不在本命题范围**（见 §10）。

## 6. 落地形态（可执行 YAML）

> **v2 修订范围**：本节 YAML 的**结构**（两个 job、job 级闸门、入口、失败取证）沿用 v1 不变；本次只改两处——① `changes` 白名单**增一行** `.github/workflows/ci.yml`（见 §5.4 Q5 裁定与下方 YAML）；② `image-guard` 的断言 step（名称与脚本改为三路径 union，见下）。

在既有 `.github/workflows/ci.yml` 中**新增两个 job**（`changes` 闸门 + `image-guard`）；既有 `build` job 与 event 级 `paths-ignore` **保持不变**。

```yaml
jobs:
  changes:
    name: Detect image-related changes
    runs-on: ubuntu-latest
    outputs:
      image: ${{ steps.filter.outputs.image }}
    steps:
      - uses: actions/checkout@v4
      - uses: dorny/paths-filter@v3
        id: filter
        with:
          # v2 / #92（Q5(b)）：白名单第 6 行 `.github/workflows/ci.yml` 为新增，
          # 使「只改 ci.yml 的 PR」也跑 image-guard；否则新断言口径在合入 PR 上零 CI 证据（守门自证缺口）。
          filters: |
            image:
              - 'Dockerfile'
              - 'docker-compose.yml'
              - 'pom.xml'
              - '**/pom.xml'
              - '.mvn/**'
              - '.github/workflows/ci.yml'

  image-guard:
    name: Image Build Guard
    needs: changes
    if: needs.changes.outputs.image == 'true'
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - name: Set up BuildKit (GHA cache)
        uses: docker/setup-buildx-action@v3
      - name: Build image via compose
        run: docker compose build app
      - name: Assert runtime contract (non-root + /data{,/media,/flowcart} writable)
        run: |
          docker run --rm --entrypoint sh flowcart/app:dev -c '
            set -e
            uid=$(id -u); echo "runtime uid = $uid"
            [ "$uid" != "0" ] || { echo "FAIL: container runs as root"; exit 1; }
            for p in /data /data/media /data/flowcart; do
              touch "$p/.writecheck" && rm "$p/.writecheck" \
                || { echo "FAIL: $p not writable by runtime user"; exit 1; }
            done
            echo "PASS: non-root + /data, /data/media, /data/flowcart writable"'
      - name: Dump image metadata on failure
        if: failure()
        run: docker image inspect flowcart/app:dev || true
      - name: Upload guard logs on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: image-guard-logs
          path: "**/target/*.log"
          retention-days: 7
          if-no-files-found: ignore
```

### 6.1 关键点

- **闸门语义**：`changes` 是无 `if` 的轻量 job（每次 workflow 跑都会执行，只做路径过滤）；`image-guard` 以 `needs.changes.outputs.image == 'true'` 为门——**仅白名单路径变更时触发**，实现 (i)+(v)。
- **不新开 workflow、不缩既有覆盖**：既有 `on.push` / `on.pull_request` 的 `paths-ignore` 黑名单（`ci.yml:15-25`）**原样保留**；白名单只作用在 `image-guard` 一个 job 上，`Build & Test (JDK 21)` 的 PR 覆盖**不变**。（不用 workflow 级 `on.pull_request.paths` 的原因见 §1。）
- **入口同源**：`docker compose build app` 走的就是部署入口（含 compose 解析），构建产物 tag 由 compose 定义（`flowcart/app:dev`，`docker-compose.yml:143`）。
- **断言口径 = 安全承诺的验收基线（v2 / #92 修订后）**：`--entrypoint sh` 覆盖 ENTRYPOINT，容器以镜像默认 `USER flowcart` 运行；`id -u ≠ 0` 直验「非 root」，再以**逐路径可写性探针**（`for p in /data /data/media /data/flowcart`）直验**已声明数据根及其子目录**对运行用户可写。
  - **结构锚定（口径选择，见 §5.4 Q2.2）**：探针用**裸 `touch`**（**不用** `mkdir -p`）⇒ **子目录缺失即红**。语义是「`Dockerfile:48` 承诺的『预建 + chown』这一结构必须成立」，而非仅「运行时最终可用」。代价：若未来合法移除子目录预建，本断言会红——但该移除本身即承诺变更，应当被发现。
  - **纯可写性、无属主 / 权限位断言（口径选择，见 §5.4 Q2.1）**：只验「运行用户能否在目标目录创建并删除文件」，不比对 `uid:gid` / 权限位。理由：`root:root 0777` 之类「属主非 flowcart 但实际可写」的形态**功能可用**，属主断言会造成假红（违反「合法镜像必绿」）；且硬编码 `999:999 755` 与基镜像 uid/gid、umask 耦合。
  - **三路径的理由**：`/data`（顶层卷挂载点）+ `/data/flowcart`（**已接线写入目标**，JSON 文档库根）+ `/data/media`（**已声明数据根**，守 `Dockerfile:48` 预建契约与规范 0007 §7.2 声明面）。`/data/media` 当前**未接线**（无代码写它）的如实登记见 §10。
  - **失败可定位**：失败信息含具体路径（`FAIL: $p not writable by runtime user`），使「哪一层属主 / 权限腐化」一眼可分。
- **构建缓存**：`docker/setup-buildx-action@v3` 确保 BuildKit；Compose v2 的 `build` 默认即走 BuildKit（E21），opt-out 仅 `DOCKER_BUILDKIT=0`。`cache-to/cache-from=gha` 为**可选**，去留**不改变本规范语义**（属 Build 期可无损叠加项）。
- **失败取证**：沿用仓库既有约定（`timeout-minutes: 20`、`failure()` 触发上传，E10）——另加 `docker image inspect`，让「构建坏了还是运行时契约坏了」一眼可分。
- **不跑 `docker compose up -d`**：见 §5.3 C3 与 §2 红线。

## 7. 验收标准（AC）

> 全部为**可判定**条目。实现由独立 build 票承担；本规范只定义验收线。

- [ ] `changes` 闸门 job 与 `image-guard` job 落地于 `.github/workflows/ci.yml`；**未新开第二个 workflow**。
- [ ] `image-guard` 仅在白名单路径（`Dockerfile` / `docker-compose.yml` / `pom.xml` / `**/pom.xml` / `.mvn/**` / **`.github/workflows/ci.yml`（v2 / #92 新增）**）变更时触发。
- [ ] 既有 `Build & Test (JDK 21)` 的触发条件与 PR 覆盖**不变**（event 级 `paths-ignore` 原样保留、单 job 结构不缩水）。
- [ ] `image-guard` 入口为 `docker compose build app`，构建产物 tag = `flowcart/app:dev`。
- [ ] 断言口径作为**「非 root + 数据根可写」安全承诺的验收基线（v2 / #92 修订后）**：一次性容器内 `id -u ≠ 0` 失败即红；`/data`、`/data/media`、`/data/flowcart` **三处中任一**的 `.writecheck` 创建 / 删除失败即红，且失败输出**含具体路径**。
- [ ] `image-guard` 失败时上传取证 artifact（`image-guard-logs`）并输出 `docker image inspect`。
- [ ] **「CI 绿」口径变更生效**：「`ci.yml`（含 `image-guard`）+ `qodana`」——即 `build` / `image-guard` / `qodana` 三者皆绿方为绿。
- [ ] **Build 首验 ①（路径闸门合并校验）**：实测 `changes` 闸门叠加后，`image-guard` **仅白名单路径变更时触发**，且既有 job 覆盖**不变**。
- [ ] **Build 首验 ②（runner 上 BuildKit 生效核验）**：以首次 run 的 `Set up job` 日志确认 `docker compose build app` 走 BuildKit（cache mount 生效），并核 `image-guard` 总耗时落于 E23 基线（1m35s–4m51s）可接受增量内。
- [ ] **Build 首验 ③（v2 / #92 新增：腐化构造下断言必红、合法镜像必绿）**：以 `Dockerfile:48` 去掉 `chown -R` 的 `-R`（或等价构造：`/data` 属 `flowcart` 而 `/data/media`、`/data/flowcart` 属 `root:root`）构造腐化镜像，实测**新断言必红**（且失败信息含路径）；在**合法镜像**上实测**三路径全绿**（无假红）。本项即 v2 修订的**可判定性自证**。
- [x] **Build 首验 ④（v2 / #92 新增：白名单增行后的触发语义实测复算）**：实测「只改 `.github/workflows/ci.yml`」的 PR **能**触发 `image-guard`（即白名单增行的**触达语义**已闭合——改 `ci.yml` 的 PR 不再整段跳过 `image-guard`；**残余边界见 §10 R2 处置列**）；同时复算 workflow 级 `paths-ignore` 与 job 级白名单叠加语义未改变既有 `Build & Test (JDK 21)` 覆盖（沿用 §8「须实测，非推理」纪律）。**（#94 / PR #97 实测成立，run `35913855857`：`Image Build Guard` = success 2m15s、paths-filter 单独命中 `.github/workflows/ci.yml`、`Build & Test (JDK 21)` 同次 run 仍实跑 success ⇒ 详见 §8.1 实测结果块）**
- [ ] 既有 `mvn clean test` 仍**全绿**（本改动不污染默认测试路径）。
- [ ] e2e **仍不进默认 CI**（#75 红线未被触碰；`image-guard` 不启动任何服务容器）。

## 8. Build 期首验动作

Build 票落地时必须先做、且结果回填本规范的三项（对应 §7 的 Build 首验 ①–④）：

1. **与既有 `paths-ignore` 的合并校验**——`changes` 闸门叠加后，确认 `image-guard` 仅在白名单路径变更时触发，且既有 `Build & Test (JDK 21)` 覆盖**无变化**（黑名单与 job 级白名单的交互语义须实测，非推理）。**（v2 / #92 扩项，对应 §7 首验 ④）** 同一次实测中须一并确认：**只改 `.github/workflows/ci.yml` 的 PR 能触发 `image-guard`**——这是 Q5(b) 增行的唯一目的，未实测即等于未验收。

   **实测结果（#94 / PR #97，run `35913855857`，已回填 ⇒ §7 首验 ④ 实测成立、§10 R2 闭合）**

   被测对象为 run `35913855857` **触发时刻**的 PR #97（head `98e7318`），彼时改动面**只有一个文件**：`.github/workflows/ci.yml`（白名单 5→6 条 + 文件头注释），paths-filter 日志 `Received 1 items` 与此一致。`Dockerfile` / `docker-compose.yml` / `pom.xml` / `.mvn/**` **全部未动** ⇒ 触发归因唯一，不存在「靠别的文件蹭到」。commit `4f82eb3` 的规范回填**发生在取证之后**，**不参与本次触发归因**（故 PR #97 最终态 Files changed 为 2 个文件，与本处「彼时仅 1 个文件」的表述不矛盾）。

   ```
   $ gh pr checks 97
   Detect image-related changes	pass	6s	…/runs/35913855857/job/107360227117
   Image Build Guard	pass	2m15s	…/runs/35913855857/job/107360713242
   Build & Test (JDK 21)	pass	2m4s	…/runs/35913855857/job/107360226728
   qodana	pending	0	…/runs/35913856238/job/107360227599   ← 取证时刻（2026-09-23 20:09）快照，非最终态：该 Qodana run `35913856238` 后已收敛为 success；§7 首验 ④ 的判据只取上面 `Image Build Guard` 一行

   $ gh run view 35913855857 --json status,conclusion,jobs --jq '.conclusion, (.jobs[]|"\(.name) | \(.conclusion) | \(.startedAt) -> \(.completedAt)")'
   success
   Build & Test (JDK 21) | success | 2026-09-23T20:07:39Z -> 2026-09-23T20:09:43Z
   Detect image-related changes | success | 2026-09-23T20:08:48Z -> 2026-09-23T20:08:54Z
   Image Build Guard | success | 2026-09-23T20:08:58Z -> 2026-09-23T20:11:13Z
   ```

   ⇒ **`Image Build Guard` 实跑且为 success（2m15s），非 skipped**；同一次 run 中 `Build & Test (JDK 21)` 仍实跑（success，2m4s）。

   触发归因（`Detect image-related changes` job / `Filter image-related paths` step 日志，`gh run view 35913855857 --log --job 107360227117`）：

   ```
   ##[group]Run dorny/paths-filter@v3
   with:
     filters: image:
       - 'Dockerfile'
       - 'docker-compose.yml'
       - 'pom.xml'
       - '**/pom.xml'
       - '.mvn/**'
       # #94：白名单含工作流自身 —— 使「只改本文件的 PR」也跑 image-guard，
       # 让守门对自己配置的改动有 CI 证据（守门自证，否则该 job 恒被跳过）。
       - '.github/workflows/ci.yml'
     token: ***
     list-files: none
     initial-fetch-depth: 100
     predicate-quantifier: some
   ##[endgroup]
   ##[group]Fetching list of changed files for PR#97 from Github API
   Invoking listFiles(pull_number: 97, per_page: 100)
   Received 1 items
   [modified] .github/workflows/ci.yml
   ##[endgroup]
   Detected 1 changed files
   Results:
   ##[group]Filter image = true
   Matching files:
   .github/workflows/ci.yml [modified]
   ##[endgroup]
   Changes output set to ["image"]
   ```

   ⇒ 白名单第 6 条 `.github/workflows/ci.yml` **单独命中**（`Detected 1 changed files` / `Filter image = true` / `Changes output set to ["image"]`），`image-guard` 的 `if: needs.changes.outputs.image == 'true'` 由此放行。

   同次 run 的 `image-guard` 断言输出（`--job 107360713242`，**注意：本票不动断言口径，仍是 #89 落地的单路径探针**）：

   ```
   Assert runtime contract (non-root + /data writable)
   runtime uid = 999
   PASS: non-root + /data writable
   ```

   **结论**：R2 的「白名单增行生效语义」由推理**升级为实测成立**；event 级 `paths-ignore`（`docs/**`、`**/*.md`、`LICENSE`、`.gitignore`）与 job 级白名单的叠加语义**未改变**既有 `Build & Test (JDK 21)` 的触发与 PR 覆盖（该 job 在同次 run 中照常实跑且为 success）。**本项（触达语义）由 #94 / PR #97 实测闭合；残余边界（删除自身 / YAML 语法失效）见 §10 R2 处置列，非本行白名单可填。**
2. **runner 上 BuildKit 生效核验**——以首次真实 run 的 `Set up job` 日志确认 `docker compose build app` 走 BuildKit（`RUN --mount=type=cache` 生效），并核 job 总耗时落于 E23 基线可接受增量内。
3. **断言可判定性自证（v2 / #92 新增，对应 §7 首验 ③）**——在 runner（或等价的本地 Docker）上构造两种镜像并实测：① 腐化镜像（`Dockerfile:48` 的 `chown -R` 去 `-R`，或等价地把两个子目录置为 `root:root`）⇒ **新断言必红且输出具体路径**；② 合法镜像 ⇒ **三路径全绿**。结果（含命令与输出摘要）回填本规范。

**fallback（事实枚举，非方案推荐）**：若某次 run 环境异常致 BuildKit 未生效，等价入口有 `DOCKER_BUILDKIT=1 docker compose build app` / `docker buildx build -f Dockerfile --load .` / `docker build`（Engine ≥23 亦默认 BuildKit）。

## 9. 文件级修改清单（相对路径）

| 类别 | 文件 | 变更 | 备注 |
|---|---|---|---|
| **改**（实现期，**本轮不改**） | `.github/workflows/ci.yml` | 新增 `changes` + `image-guard` 两个 job（§6）；**v2 / #92 追加**：① 修订 `image-guard` 断言口径为三路径 union（§6）；② 把 `.github/workflows/ci.yml` 加入 `changes` 白名单（§6 / §7） | 唯一需要改动的实现文件 |
| **不改** | `Dockerfile` | 无 | 本规范不改镜像内容架构 |
| **不改** | `docker-compose.yml` | 无 | 复用既有 `app` 服务 build / image 定义 |
| **不改** | `.github/workflows/qodana_code_quality.yml` | 无 | 「CI 绿」口径变更只是语义层（三者皆绿），不新增/改动该文件 |
| **增**（v1）／**改**（v2） | `docs/specs/0008-ci-image-build-guard.md` | v1 新建；**v2（#92）就地修订**：header 状态推进、§1 断言口径、§5.4–§5.5 新增（候选 / 评分 / 被拒 / 判据来源）、§6 断言 step 与白名单行、§7 AC（含新增首验 ③④）、§8 首验三项、§10 未决登记、§11 回滚三路径、§13 卷语义边界 | 本文件（决策记录 `DR-FLOWCART-0092-01`） |
| **建议同步**（另立，本轮不写） | `docs/architecture.md`、`README.md` | 见 §12 | 由后续文档票承担 |

**数据清理范围**：无。**表结构变更与迁移脚本**：无。

## 10. 风险与残留未知

| 编号 | 项 | 分级 | 处置 |
|---|---|---|---|
| U3a | E16–E20 为查询日快照（2026-09-23），runner 镜像滚更可能改变 Docker / Compose / Buildx 版本 | `non-blocking` | Build 首验 ② 复核；版本在不改变可行性结论的范围内 rota 无碍 |
| U3b | 无本仓在真实 GHA runner 上跑 `docker compose build` 的实测记录，「默认走 BuildKit」整体强度 = 官方文档 + 机制反证 | `non-blocking` | Build 首验 ② 以首次 run 的 `Set up job` 日志终局验证 |
| E5 | 「build 成功不校验 `USER`/`chown`」为**推断**（缺构造性反例） | `non-blocking` | 不影响 C2 断言设计（断言直验结果，不依赖该推断） |
| Q2 子问题 | 「命名卷持久化」断言**默认【不追加】**，标 `assumed`——「可持久化」由 compose 卷定义（`app_data:/data`，E6）承载，**不属 `Dockerfile` 承诺**；`Dockerfile` 侧承诺是「非 root + `/data` 预建并 chown」 | `non-blocking` | 登记为 Build 票**可选增强**（`docker run -v` 临时卷二次断言，可无损叠加） |
| 可选 | `cache-to/cache-from=gha` 是否启用 | `non-blocking` | 去留不改变本规范语义 |
| E9 | #86 声称的本机 `docker compose build app` ~50s **未一手复现** | `non-blocking` | 耗时以 E23（本仓 CI 实测）为预算基线，不采信 E9 数值 |
| **R1**（v2 新增） | **锚定口径歧义（全案置信度最低项 0.74，真人可否决点）**：「**结构锚定**」（子目录缺失即红，本规范选定）vs「**可用性锚定**」（`mkdir -p "$p"` 后探）。二者在同一「子目录缺失」镜像上结论相反；但该镜像下应用**实际可用**（`JsonFileOrderStore` / `JsonFilePublishStateStore` 写前均有 `Files.createDirectories`） | `non-blocking`（残余） | **翻盘条件**：若认定「合法镜像应按**功能口径**（惰性自建即合法）」⇒ 断言改用 `mkdir -p "$p"` 后探，并同步改 §1 / §6 / §7。此活口未决前**不改实现** |
| **R2**（v2 新增，**触达语义已由 #94 / PR #97 实测闭合；残余边界见处置列**） | **Q5(b) 白名单增行的生效语义为推理**：workflow 级 `paths-ignore` 不含 `.github/**`（改 `ci.yml` 会触发 workflow），但「job 级白名单叠加后 `image-guard` 确能放行」**须实测**（黑名单与 job 级白名单的交互语义，沿用 §8 纪律） | `non-blocking` → **触达语义实测闭合** | Build 首验 ④ 实测复算（§7 / §8.1）；未实测前不得宣称「守门自证缺口已闭合」。**实测结论（run `35913855857`）**：只改 `ci.yml` 的 PR #97 上 `Image Build Guard` **实跑且 success（2m15s，非 skipped）**，paths-filter `Filter image = true` / `Matching files: .github/workflows/ci.yml [modified]`（取证时刻全 PR 仅此 1 个改动文件）⇒ **触达语义闭合**（#94 定义的空洞——改 `ci.yml` 的 PR 会让 `image-guard` 整个被跳过——已消失）；`Build & Test (JDK 21)` 同次 run 仍实跑 success ⇒ 叠加语义未缩既有覆盖。详见 §8.1。**残余边界（本行白名单盖不住，显式登记）**：① **不可自守「删除自己」**——filters 由 `dorny/paths-filter` 从**被测 PR 自己的检出**读取（证据：同一 run 的 dorny 日志回显 **6 条** filters，而 base `main` 的 `ci.yml` 只有 **5 条** ⇒ 可据此判定所读为被测 PR 侧文件）；故若某 PR 主动删掉 `- '.github/workflows/ci.yml'` 这一行，该 PR 自身的这次改动会把**自己**豁免出 `image-guard`——**删除动作本体拿不到任何 CI 证据**。② **语法级失效不被覆盖**——某次改动使 `ci.yml` 的 YAML 不合法 ⇒ workflow 整体不触发 ⇒ 同样零证据。二者只能由 **code review / branch protection** 兜底，**非本行白名单可填**；本仓 `main` 当前**未启用分支保护**（复算：`GET /repos/luochenfx/ecom-flowcart/branches/main/protection` = 404 `Branch not protected`）⇒ 当前兜底为纯人工 |
| **R3**（v2 新增，**越界登记 / 建议另立票**） | **媒体根未接线缺陷（不在本命题范围）**：`app.media-root: /data/media` 的声明**未被 app 代码消费**；生产媒体根由内容 SPI provider 从环境变量 `FLOWCART_MEDIA_ROOT` 取（缺省**相对** `target/media`），而 compose 的 `app` 服务**未设该变量** ⇒ 容器内媒体根落 `/app/target/media`，`/app` 属 `root:root` ⇒ 运行用户**实测不可创建**（内容链 `media.process` 一旦执行即失败） | `non-blocking`（本命题外） | **另立票**修接线（`Dockerfile` `ENV` 或 compose `environment`）。**本规范不顺手做**（见 §13） |
| **R4**（v2 新增） | **术语歧义**：本仓「真实写入目标」一词同时被用于 ① **已接线写入目标**（`app.data-root` → `/data/flowcart`，有代码真写）与 ② **已声明数据根**（`app.media-root: /data/media`，声明未接线） | `non-blocking` | 建议在 `CONTEXT.md` 术语表区分二者（glossary 变更，非本规范职责；本规范仅登记） |
| **R5**（v2 新增） | **`/data/media` 纳入路径集合的判据已如实登记**（见 §5.5）：系守 `Dockerfile:48` 的预建 + chown 契约与规范 0007 §7.2 的声明面，**非**守「已接线行为」（该路径当前无代码写） | `non-blocking` | 若后续 R3 票把 `app.media-root` 接线到 `FLOWCART_MEDIA_ROOT`，本行改述为「守已接线行为」，**断言本身无需变更** |

**blocking 未知点：0。**

> 上表 `R*` 行均为 **v2（#92 修订）新增**；`U3a` / `U3b` / `E5` / `Q2 子问题` / `可选` / `E9` 为 v1 原有登记，原样保留。
>
> **v2 收敛重算（#92）**：Q1 = 三路径 union、Q2.1 = 纯可写性、Q2.2 = 结构锚定、Q3 = `ci.yml` 内联、Q4 = 就地修订本规范、Q5 = 白名单增行——**blocking 未知点全部清零**；残余「锚定口径歧义」（R1）与「白名单执行语义」（R2）均为 `non-blocking`。综合置信度 **0.86**；五条件核验满足 ⇒ `need_human = false`（主理人复核后维持不升级）。**透明注**：若将「为安全承诺定义验收口径」认定为命中「安全」类，则五条件第 ④ 条翻为不满足 ⇒ `need_human = true`；此判定权在真人。
>
> **附注（须随 Build 票一并执行，见 §7 首验 ③④ / §8）**：R2 的「白名单增行是否真能触发 `image-guard`」**执行语义待 Build 首验 ④ 实测复算**，未复算前脚本与白名单的落地正确性只能视为**推理成立**；R1 以「真人可否决点」身份活口保留，附翻盘条件、未决前不改实现。
>
> **（#94 / PR #97 后追加）** **R2 的触达语义已实测闭合**：run `35913855857` 上 `Image Build Guard` 实跑且 success、paths-filter 单独命中 `.github/workflows/ci.yml` ⇒ 「白名单增行的落地正确性」由**推理成立**升级为**实测成立**。**不得据此宣称「守门自证缺口已闭合」**：本行白名单仍有两条残余边界——① filters 读自被测 PR 自身检出 ⇒ **删除本行的 PR 自身不被本守门覆盖**；② `ci.yml` YAML 语法失效致 workflow 整体不触发 ⇒ 亦无证据——二者由 review / branch protection 兜底（本仓 `main` 未启用分支保护），**非本行可填**（详见上表 R2 处置列）。R1 仍为活口（本票 #94 **只做触达面，不动断言口径**；三路径 union 属 #95）。

## 11. 回滚 / 可否决与契约影响

- **回滚（v1 原有，粒度最粗）**：删除 `.github/workflows/ci.yml` 中的 `changes` 与 `image-guard` 两个 job 块即可，**零语义残留**。或退到「仅 `push(main)` 守门」（去掉 `changes` 闸门对 PR 的限定语义）。
- **回滚（v2 / #92，三条，按粒度排列）**：
  1. **`for` 块删除（最小）**：把 §6 断言 step 的 `for p in /data /data/media /data/flowcart; do … done` 改回 v1 的单探 `touch /data/.writecheck && rm /data/.writecheck`，其余（含白名单增行）不动。适用：R1 口径歧义被真人否决、或新断言在真实 runner 上出现未预期假红。
  2. **白名单行删除**：删除 §6 YAML 中 `- '.github/workflows/ci.yml'` 一行（`.github/workflows/ci.yml:90-96` 同步），退回「改 `ci.yml` 的 PR 不自证」，并按 §10 R2 附注改由 §8.1 人工 / 临时触发验收。
  3. **§6 · §7 · §10 撤销**：撤销本规范 §6 断言口径与白名单行、§7 AC（含新增首验 ③④）、§10 的 `R1`–`R5` 登记（连带 §5.4–§5.5 与 header 的 v2 增补），即回到 v1 文本（header 状态回退为「v1 设计期决议」）。
- **真人否决权**：真人可随时否决本决策；若改选其他形态（含 R1 翻盘为「可用性锚定」），须重出 DecisionRecord。
- **契约影响声明**：**零** `core-contracts` 字段 / 方法签名变更，不触 `AGENTS.md`「core 改动即中止」闸门。本规范改动面在 CI 配置与文档，与业务模块契约无关。

## 12. 需同步的文档（建议，本轮不写）

| 文档 | 建议变更 |
|---|---|
| `docs/architecture.md` §规范索引 | 加 0008 一行 |
| `README.md` | 状态推进 / 说明「CI 绿」= `build` + `image-guard` + `qodana` |

（上述属文档票范围，本规范仅**提出建议**，不代替写。）

## 13. 明确不做的事（范围外）

以下问题**本规范不解决**，实现时若遇到**不要顺手做**：

| 项 | 归属 |
|---|---|
| #75 e2e 作业编排（含任何 `docker compose up -d`） | #75 |
| 镜像内容架构重写（多阶段结构、层序优化） | 后续（本规范只守门，不改镜像） |
| CI runner 成本治理 | 后续 |
| 销售 / 订单侧生产 Adapter（#74 已知缺口） | 后续 |
| 命名卷持久化断言（`docker run -v` 二次断言；含**只读挂载** `:ro` 等**卷**语义） | Build 票**可选增强**（见 §10）。**v2 / #92 明确**：本规范断言**只覆盖「镜像内文件系统」的可写性**，「卷」层面的语义（只读挂载、跨容器持久化、宿主 bind 属主映射）**仍属范围外**——不得因本次扩路径而顺手纳入 |
| 媒体根未接线缺陷（`app.media-root` 未被 app 代码消费 / compose 的 `app` 服务未设 `FLOWCART_MEDIA_ROOT`） | **另立票**（配置接线修复；登记见 §10 R3）。本规范只**登记事实**，不顺手接线 |
| `cache-to/cache-from=gha` | Build 期可无损叠加项（见 §6.1） |
