# 规范：CI 镜像构建守门（build + 轻量运行时断言）

> 来源：Grilling session（2026-09-23），议题「CI 应以何种形态为镜像构建加守门」（GitHub issue [#86](https://github.com/luochenfx/ecom-flowcart/issues/86)）
> 依赖：[规范 0007（端到端链路打通）](./0007-end-to-end-flow-assembly.md)（`Dockerfile` 与 compose `app` 服务的来源）、issue [#74](https://github.com/luochenfx/ecom-flowcart/issues/74)（PR #85 终审遗留登记）、issue [#75](https://github.com/luochenfx/ecom-flowcart/issues/75)（e2e 验收）
> 状态：v1 设计期决议（**已获真人授权**；实现另立 build 票，**本规范不改任何实现文件**）
> 范围声明：本规范**只解决「镜像构建这一环节的 CI 守门形态」**。以下明确**不在本规范范围**（见 §13）：#75 e2e 作业编排、镜像内容架构重写、CI runner 成本治理、销售/订单侧生产 Adapter。

## 1. 决策概览

- **采纳 C2：build + 轻量运行时断言**——`docker compose build app` 冒烟通过后，用一次性容器直验 `Dockerfile` 承载的运行时契约（非 root + `/data` 可写）。评分矩阵 **C2 4.60 > C3 3.30 > C1 3.13 > C0 2.80**（7 准则加权，权重和 1.00，含权重敏感度检验「排序稳健」）。
- **守门语义深度 = (b)**：构建成功 + 镜像运行时轻断言（不止 `exit=0`）。
- **门禁位置与触发 = (i)+(v)**：纳入 **PR 门禁**，且以 **paths 白名单**限定触发面（仅镜像相关路径变更触发）。
- **job 落点结构 = (B)**：**在既有 `ci.yml` 内新增独立 job**，**不新开第二个 workflow**。
- **(i)+(v) 的忠实实现方式 = job 级白名单闸门**：因 GHA 规定 `paths` 与 `paths-ignore` **不可在同一 event 并用**，且 **workflow 级** `paths` 会连带限缩既有 `Build & Test (JDK 21)` 的 PR 覆盖，故**不用** workflow 级 `on.pull_request.paths`，改为「轻量 `changes` 闸门 job（`dorny/paths-filter@v3`）+ `image-guard` 以 `needs.changes.outputs.image` 为 `if`」（见 §6）。既保住白名单语义，又不新开 workflow、不缩小既有 job 覆盖。
- **入口与断言**：入口 = `docker compose build app`（与部署入口同源，含 compose 解析）；镜像 tag = `flowcart/app:dev`（`docker-compose.yml:143`）；断言 = 运行用户 `id -u ≠ 0` **且** `/data/.writecheck` 可创建可删除。
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

## 6. 落地形态（可执行 YAML）

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
          filters: |
            image:
              - 'Dockerfile'
              - 'docker-compose.yml'
              - 'pom.xml'
              - '**/pom.xml'
              - '.mvn/**'

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
      - name: Assert runtime contract (non-root + /data writable)
        run: |
          docker run --rm --entrypoint sh flowcart/app:dev -c '
            set -e
            uid=$(id -u); echo "runtime uid = $uid"
            [ "$uid" != "0" ] || { echo "FAIL: container runs as root"; exit 1; }
            touch /data/.writecheck && rm /data/.writecheck || { echo "FAIL: /data not writable by runtime user"; exit 1; }
            echo "PASS: non-root + /data writable"'
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
- **断言口径 = 安全承诺的验收基线**：`--entrypoint sh` 覆盖 ENTRYPOINT，容器以镜像默认 `USER flowcart` 运行；`id -u ≠ 0` 直验「非 root」，`touch /data/.writecheck && rm` 直验「`/data` 对运行用户可写」。
- **构建缓存**：`docker/setup-buildx-action@v3` 确保 BuildKit；Compose v2 的 `build` 默认即走 BuildKit（E21），opt-out 仅 `DOCKER_BUILDKIT=0`。`cache-to/cache-from=gha` 为**可选**，去留**不改变本规范语义**（属 Build 期可无损叠加项）。
- **失败取证**：沿用仓库既有约定（`timeout-minutes: 20`、`failure()` 触发上传，E10）——另加 `docker image inspect`，让「构建坏了还是运行时契约坏了」一眼可分。
- **不跑 `docker compose up -d`**：见 §5.3 C3 与 §2 红线。

## 7. 验收标准（AC）

> 全部为**可判定**条目。实现由独立 build 票承担；本规范只定义验收线。

- [ ] `changes` 闸门 job 与 `image-guard` job 落地于 `.github/workflows/ci.yml`；**未新开第二个 workflow**。
- [ ] `image-guard` 仅在白名单路径（`Dockerfile` / `docker-compose.yml` / `pom.xml` / `**/pom.xml` / `.mvn/**`）变更时触发。
- [ ] 既有 `Build & Test (JDK 21)` 的触发条件与 PR 覆盖**不变**（event 级 `paths-ignore` 原样保留、单 job 结构不缩水）。
- [ ] `image-guard` 入口为 `docker compose build app`，构建产物 tag = `flowcart/app:dev`。
- [ ] 断言口径作为**「非 root + `/data` 可写」安全承诺的验收基线**：一次性容器内 `id -u ≠ 0` 失败即红；`/data/.writecheck` 创建/删除失败即红。
- [ ] `image-guard` 失败时上传取证 artifact（`image-guard-logs`）并输出 `docker image inspect`。
- [ ] **「CI 绿」口径变更生效**：「`ci.yml`（含 `image-guard`）+ `qodana`」——即 `build` / `image-guard` / `qodana` 三者皆绿方为绿。
- [ ] **Build 首验 ①（路径闸门合并校验）**：实测 `changes` 闸门叠加后，`image-guard` **仅白名单路径变更时触发**，且既有 job 覆盖**不变**。
- [ ] **Build 首验 ②（runner 上 BuildKit 生效核验）**：以首次 run 的 `Set up job` 日志确认 `docker compose build app` 走 BuildKit（cache mount 生效），并核 `image-guard` 总耗时落于 E23 基线（1m35s–4m51s）可接受增量内。
- [ ] 既有 `mvn clean test` 仍**全绿**（本改动不污染默认测试路径）。
- [ ] e2e **仍不进默认 CI**（#75 红线未被触碰；`image-guard` 不启动任何服务容器）。

## 8. Build 期首验动作

Build 票落地时必须先做、且结果回填本规范的两项：

1. **与既有 `paths-ignore` 的合并校验**——`changes` 闸门叠加后，确认 `image-guard` 仅在白名单路径变更时触发，且既有 `Build & Test (JDK 21)` 覆盖**无变化**（黑名单与 job 级白名单的交互语义须实测，非推理）。
2. **runner 上 BuildKit 生效核验**——以首次真实 run 的 `Set up job` 日志确认 `docker compose build app` 走 BuildKit（`RUN --mount=type=cache` 生效），并核 job 总耗时落于 E23 基线可接受增量内。

**fallback（事实枚举，非方案推荐）**：若某次 run 环境异常致 BuildKit 未生效，等价入口有 `DOCKER_BUILDKIT=1 docker compose build app` / `docker buildx build -f Dockerfile --load .` / `docker build`（Engine ≥23 亦默认 BuildKit）。

## 9. 文件级修改清单（相对路径）

| 类别 | 文件 | 变更 | 备注 |
|---|---|---|---|
| **改**（实现期，**本轮不改**） | `.github/workflows/ci.yml` | 新增 `changes` + `image-guard` 两个 job（§6） | 唯一需要改动的实现文件 |
| **不改** | `Dockerfile` | 无 | 本规范不改镜像内容架构 |
| **不改** | `docker-compose.yml` | 无 | 复用既有 `app` 服务 build / image 定义 |
| **不改** | `.github/workflows/qodana_code_quality.yml` | 无 | 「CI 绿」口径变更只是语义层（三者皆绿），不新增/改动该文件 |
| **增**（本规范自身） | `docs/specs/0008-ci-image-build-guard.md` | 新建 | 本文件 |
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

**blocking 未知点：0。**

## 11. 回滚 / 可否决与契约影响

- **回滚**：删除 `.github/workflows/ci.yml` 中的 `changes` 与 `image-guard` 两个 job 块即可，**零语义残留**。或退到「仅 `push(main)` 守门」（去掉 `changes` 闸门对 PR 的限定语义）。
- **真人否决权**：真人可随时否决本决策；若改选其他形态，须重出 DecisionRecord。
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
| 命名卷持久化断言（`docker run -v` 二次断言） | Build 票**可选增强**（见 §10） |
| `cache-to/cache-from=gha` | Build 期可无损叠加项（见 §6.1） |
