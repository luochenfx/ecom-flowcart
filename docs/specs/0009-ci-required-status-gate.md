# 规范：CI required status check 门禁（always-run gate）

> 来源：Grilling session（2026-09-24），议题「在 `main` 已启用 required status checks 的前提下，docs-only PR 为何永久 pending、如何修复该自指死锁」（GitHub issue [#99『Chore: main 分支启用分支保护（required status checks 兜住 CI 门禁）』](https://github.com/luochenfx/ecom-flowcart/issues/99)）
> 依赖：[规范 0008（CI 镜像构建守门）](./0008-ci-image-build-guard.md)（`ci.yml` 的 `changes` + `image-guard` 两 job、「CI 绿」三件套口径、`Image Build Guard` check 的来源与 `paths-ignore` 黑名单）、issue [#94](https://github.com/luochenfx/ecom-flowcart/issues/94) / PR [#97](https://github.com/luochenfx/ecom-flowcart/pull/97)（守门自证触达面）、PR [#98](https://github.com/luochenfx/ecom-flowcart/pull/98)（其一分支 `docs-only` head `395578b9` 的「零 check」实测）
> DecisionRecord：`DR-flowcart-0099-main-pending-02`（**已收敛**：`need_human = false`；blocking 未知点 0；采取裁定 **C3-β**）
> 状态：**v1（设计期决议）** —— 实现另立 build 票承担（步1 PR-1 / 步3 ruleset `PUT` / 步4 PR-2 / 步5 trail 实测），**本规范不改任何实现文件**。
> 范围声明：本规范**只解决「`main` 分支保护下 required status checks 与 `paths-ignore` 的交互死锁」这一命题**。以下明确**不在本规范范围**（见 §13）：#94-①「守门不可自守删除自己」、启用 `require_code_owner_review`、媒体根接线缺陷、`ci.yml` / `qodana_code_quality.yml` 的 `paths-ignore` 治理。

## 1. 决策概览

- **问题一句话**：`main` 已启用 required status checks（三件套 `Build & Test (JDK 21)` / `Image Build Guard` / `Qodana for JVM`），而两条 workflow 的 `pull_request` 触发面均带 `paths-ignore`（`docs/**`、`**/*.md`、`LICENSE`、`.gitignore`）⇒ **纯文档 PR 零 check 上报** ⇒ 在「三件套 required」下**永久 pending、不可合入**；而本仓 spec 落库历来就是 docs-only ⇒ **自指死锁**（本仓「写规范的 PR」被「规范带来的门禁」挡死）。
- **采纳 C3-β：新增 always-run 轻量 gate + required 收窄为单条 `CI Gate`**。
  1. **新增** `.github/workflows/ci-gate.yml`：常跑轻量 workflow，**job 名逐字 `CI Gate`**，触发 `on: pull_request` + `on: push: branches: [main]`，**无 `paths` / `paths-ignore` / `if` / `needs`**（自包含 ⇒ 不落入上游失败级联的静默绿陷阱）。见 §6。
  2. **ruleset `23927156` 变更（配置，非文件）**：`required_status_checks.required_status_checks := [{"context":"CI Gate","integration_id":15368}]`；`strict_required_status_checks_policy` 与其余三条规则、`bypass_actors` **均不变**。见 §6.2。
  3. **不改** `ci.yml` 与 `qodana_code_quality.yml`（`paths-ignore` 与 `image-guard` 的 job 级 `if` 全部原样保留）⇒ 文档 PR 不跑重 CI，符合真人「不接受 C4 成本」的选择。
- **「CI 绿」口径改写（v3）= 门禁层 + 信号层**（见 §5.4）：门禁层（blocking）= 单条 `CI Gate`；信号层（non-blocking）= 三件套仍按原触发面运行，但**不再阻断合并**。红线由门禁层 `CI Gate` 承担。
- **唯一安全执行序列**：步1（PR-1 增 gate workflow，非 docs）→ 步2（合并，`CI Gate` 注册）→ 步3（ruleset `PUT` 收窄 required）→ 步4（PR-2 改 docs 口径）→ 步5（trail 实测，验收用、零残留）。见 §8。**Q4「改 required 需先注册新 check」的死锁在 C3 下不成立**：步1 的 PR-1 改动不含任何被 `paths-ignore` 匹配的路径 ⇒ 旧三件套照常上报 ⇒ PR-1 自身可合入。
- **被拒候选 5 类**（C1 / C2 / C3-α / C3-γ / C5 / C6）：见 §5.3。**C4 正式出局但保留在候选集**作为记录在案的备选。
- **已知代价（显式声明、由真人承担）**：三件套红了不再阻断合并；docs-only PR 上三件套连信号都没有；`CI Gate` 是**活性令牌**、不校验任何实质内容。见 §5.5。
- **零契约变更**：本规范不触碰 `core-contracts` 的字段 / 方法签名，不触发 `AGENTS.md` 的「core 改动即中止」闸门（见 §11）。

## 2. 来源与关系（红线）

| 载体 | 与本规范的关系 | 红线 |
|---|---|---|
| **#99**（本规范命题载体） | 登记「docs-only PR 在 required status checks 下永久 pending、不可合入」的死锁；其 AC①–④ 由本规范细化定稿（见 §7） | —— |
| **规范 0008**（依赖） | 定义了「CI 绿」三件套（`build` / `image-guard` / `qodana`）与 `Image Build Guard` check；本规范**不改其触发面**，只在语义层把「阻断合并」的职责从三件套移到 `CI Gate` | 0008 的 `paths-ignore` 黑名单与 `image-guard` 的 job 级 `if` **原样保留**，本规范不触碰 |
| **#94 / PR #97**（来源） | 「守门自证」触达面：`ci.yml` 白名单含工作流自身 | 其残余边界（**守门不可自守删除自己**）**仍是独立 build 票**，本规范不解决（见 §13） |
| **PR #98**（证据来源） | 提供「docs-only PR 零 check」的一手实测语境 | 本规范**不改** `0008` 正文，仅在其 §7 / §1 口径于**步4**做同步改写（属 PR-2 范围，见 §8 / §9） |

**边界一句话**：本规范处理的是**「required status checks 的构成」**（谁在 required 集合里），**不是**任何 workflow 的触发面治理（那是 0008 与 #94 的地盘）。

## 3. 命题与范围

### 3.1 命题（in）

| 维度 | 内容 |
|---|---|
| 命题 | 在 `main` **已启用** required status checks、且候选 required check 的 workflow 均带 `paths-ignore` 的前提下，应以何种形态消除「docs-only PR 永久 pending、不可合入」的死锁 |
| In | required status checks 的**目标构成**（哪些 context 进 required 集合）+ 为达成它所需的**新 workflow 形态**与 **ruleset 变更**+ 与之配套的「CI 绿」口径改写 + 唯一安全执行序列 |
| Out | 三件套 workflow 的 `paths-ignore` 治理（C4 范畴）；#94-① 守门自证边界；`require_code_owner_review` 启用；媒体根接线（规范 0008 §10 R3）；CI runner 成本治理 |

### 3.2 收敛状态

- blocking 未知点 **0**；候选集**稳定**（C1 / C2 / C3-α / C3-β / C3-γ / C4 / C5 / C6 已覆盖「required 构成」的完整谱系）；`need_human = false`（真人已显式知情承接，含「不接受 C4 成本」与「授权步3 ruleset `PUT`」两项）；裁定 **C3-β**。综合置信度见 DecisionRecord `DR-flowcart-0099-main-pending-02`。

## 4. 现状与证据

### 4.1 死锁现状

`main` 的 required status checks 与 `paths-ignore` 的交互：

| 环节 | 事实 | 后果 |
|---|---|---|
| required 集合 | ruleset `23927156` 要求三件套（`Build & Test (JDK 21)` / `Image Build Guard` / `Qodana for JVM`），`strict_required_status_checks_policy=true` | 三件套**须全部上报 success** 才可合入 |
| 触发面 | `ci.yml` 与 `qodana_code_quality.yml` 的 `on.pull_request` / `on.push` 均带 `paths-ignore: [docs/**, **/*.md, LICENSE, .gitignore]` | 纯文档 PR **不触发**这两条 workflow ⇒ 其 check **不产生** |
| 死锁 | workflow 因 `paths` / `paths-ignore` 被跳过 ⇒ 关联 check **停 Pending 并阻断合并**（官方语义，见 E6） | docs-only PR **永久 pending**；而本仓 spec 落库历来就是 docs-only ⇒ 自指死锁 |

### 4.2 证据台账

| 编号 | 引用 | 得到的结论 | 可信度 |
|---|---|---|---|
| E1 | `GET /repos/luochenfx/ecom-flowcart/rulesets/23927156`（实测） | ruleset `id=23927156` `name=main-protection-ci-gates`、`enforcement=active`、`conditions.ref_name.include=["~DEFAULT_BRANCH"]`；rules = `deletion` + `non_fast_forward` + `pull_request`（`required_approving_review_count=0`、`allowed_merge_methods=[merge,squash,rebase]`、`require_extra_approval_for_unattributed_changes=true`、`require_code_owner_review=false`）+ `required_status_checks`（`strict_required_status_checks_policy=true`、`contexts=[Build & Test (JDK 21), Image Build Guard, Qodana for JVM]`、`integration_id=15368`）；`bypass_actors=[]`、`current_user_can_bypass="never"` | 高（一手 API） |
| E2 | `GET /repos/luochenfx/ecom-flowcart/branches/main/protection`（实测） | **经典保护为空**（恒 404）⇒ 保护**全部由 ruleset 承担** | 高（一手 API） |
| E3 | `ci.yml` 的 `on.pull_request.paths-ignore` 与 `on.push.paths-ignore`（实测） | 黑名单 = `docs/**`、`**/*.md`、`LICENSE`、`.gitignore` | 高（一手文件） |
| E4 | `qodana_code_quality.yml` 的 `on.pull_request.paths-ignore` 与 `on.push.paths-ignore`（实测） | 黑名单同 E3 | 高（一手文件） |
| E5 | PR #98 其一分支 `docs-only` head `395578b9` 的 check-runs（实测） | `total_count=0` —— **该 docs-only PR 上零 check** | 高（一手 API 实测） |
| E6 | GitHub Docs《Troubleshooting required status checks》（官方文档） | **workflow** 因 path / branch / commit-message 过滤被跳过 ⇒ 关联 check 停 **Pending 并阻断合并**（官方修法「Avoid requiring workflows that can be skipped」）；**job** 因 conditional 被跳过 ⇒ 报 **Success**；job 依赖失败的 job ⇒ 被跳过且**可能不阻合并**（修法 `always()` + `needs`）；required check 须**近 7 天内成功完成过**；check 由 workflow job 产生时事件须在合格清单内（`push` / `pull_request` / `pull_request_review` / `pull_request_target` / `deployment` / `deployment_status`）；成功状态集合 = `success` / `skipped` / `neutral` | 高（官方文档） |
| E7 | `Qodana for JVM` check 的 API 形态（实测） | 系 **Qodana action 经 Checks API 自报**：`details_url=…/runs/…`、`external_id=""`、瞬时、`output.title="No new problems found by Qodana for JVM"` | 高（一手 API） |
| E8 | job 级 check `qodana` 的 API 形态（实测） | `details_url=…/job/…`、`external_id=<UUID>`、steps 非空 —— 与 E7 **同 app 15368 但产生机制不同**；ruleset 里 required 的是**动作自报名**（`Qodana for JVM`），不是 job 名（`qodana`） | 高（一手 API） |
| E9 | 规范 0008 §7（AC 行）（实测） | 现行「CI 绿」口径 = `ci.yml`（含 `image-guard`）+ `qodana`，即 `build` / `image-guard` / `qodana` 三者皆绿 | 高（一手文件） |
| E10 | `docs/architecture.md`「规范索引」（实测） | 仍写 `0001–0007`（**陈旧**，缺 0008） | 高（一手文件） |
| E11 | `.github/` 目录内容（实测） | `.github/` 下**仅 `workflows/`** ⇒ 本仓**无 CODEOWNERS 文件** | 高（一手文件） |

> **行号锚定注（承 #98 纪律）**：本规范**不以 `ci.yml` / `qodana_code_quality.yml` 的行号作定位依据**，一律按 `on.*.paths-ignore`、job / step **名称**锚定。行号会随任何一次文件增补而漂移（#88 / #89 / #94 / #98 已多次验证），是结构性漂移源，非偶发漏改。

## 5. 决策结论与被拒候选

### 5.1 选定：C3-β

| 准则 | 结论 |
|---|---|
| 与命题贴合 | 直接消除死锁根因——required 集合里**不再有**任何会被 `paths` / `paths-ignore` 跳过的 check |
| 与既有 job 关系 | **不动**三件套的触发面与覆盖；只新增一条自包含 gate 并收窄 required 集合 |
| 自包含性 | gate workflow **无 `paths` / `if` / `needs`** ⇒ 不落「上游被跳过 → 下游静默绿」陷阱（E6 的失败级联语义） |
| 成本 | gate 为轻量 job（`timeout-minutes: 5`），PR 上恒定增量可忽略；docs PR 不触发重 CI（真人拒绝 C4 的依据） |
| 可逆性 | 撤销 = 删除 gate workflow + 把 ruleset required 恢复为旧三件套（见 §11） |
| 可观测性 | `CI Gate` 为恒定上报的 check，其 success 即「门禁层通过」的唯一判据 |

### 5.2 ruleset 目标态

`required_status_checks.required_status_checks := [{"context":"CI Gate","integration_id":15368}]`；`strict_required_status_checks_policy=true`、其余三条规则、`bypass_actors=[]` **全部不变**。即：**只有 `CI Gate` 进 required 集合**，三件套退出 required（仍运行，但只作信号）。

### 5.3 被拒候选（保留理由与依据）

| 候选 | 一句话形态 | 拒因 | 依据 |
|---|---|---|---|
| **C1** | workflow 级过滤**下沉** job 级 `if`（两 workflow 的 `paths-ignore` 改为 job 级 conditional） | **对 `Qodana for JVM` 大概率无效**——它是 action **自报** check（E7），job 被 `if` 跳过 ⇒ action 不执行 ⇒ 该 check 不产生；官方语义只覆盖「job **自身**因 conditional 跳过 ⇒ 报 Success」（E6），**不覆盖「job 内 action 自报的 check」** | E6、E7、E8 |
| **C2** | always-run **聚合** gate 作唯一 required，`if: always()` + `needs` 全量三件套 | **跨 workflow 无法 `needs`**；改 `workflow_run` 时上游被 `paths` 跳过（无 run）则**不触发** ⇒ 回死锁；且**引入新 required context 必先经历顺序死锁** | E6（`needs` 与 `always()` 语义） |
| **C3-α** | required 置空（不要求任何 check） | **门禁归零**（当前审批数 0 ⇒ 开 PR 即可合并），与 #99 目标相悖 | E1（`required_approving_review_count=0`） |
| **C3-γ** | 保留 `Build & Test (JDK 21)` 为 required + 移除 `ci.yml` 的 `on.pull_request.paths-ignore` | **实为 C4 的 `ci.yml` 半支** ⇒ 仍付真人拒绝的全量 Maven 成本 | 见 C4 拒因 |
| **C4**（**正式出局，但保留在候选集**） | 移除两 workflow 的 `on.pull_request.paths-ignore`，三件套保持 required | **门禁强度最高**，但**代价 = 每个 docs-only PR 都跑全量 Maven + Qodana** ⇒ 真人显式「不接受该成本」。**机制无损、可随时回退到 C4**（见 §11 / §13） | E3、E4 |
| **C5** | 调 ruleset：`strict=false` / 移除或替换 required / 把 owner 加入 `bypass_actors` | `strict` 只管「up-to-date」、**与本命题无关**；其余属**放弃门禁**，与 #99 目标相悖；其中加 `bypass` 还会**恶化 #94-①** | E1 |
| **C6** | 同名 job 的反向 `paths-ignore` workflow（用同名 job 在另一 workflow 里补报） | 官方 workaround 仅对 **job** 类 check 成立（E6），对 action 自报的 `Qodana for JVM` **不适用**（E7）；且**双 workflow 同名 job 语义脆弱** | E6、E7 |

### 5.4 「CI 绿」口径改写（v3，步4 落规范 0008）

> **「CI 绿」口径（v3）＝ 门禁层 + 信号层**：
> - **门禁层（blocking）**：ruleset `main-protection-ci-gates` 的 required status checks **收窄为单条 `CI Gate`**（always-run 轻量 job，无路径过滤、无 `if`、无 `needs`）；任何 PR 只要 `CI Gate` 成功即可合入。
> - **信号层（non-blocking）**：`Build & Test (JDK 21)`、`Image Build Guard`、`Qodana for JVM` 仍按原触发面在 PR 上运行（纯文档 PR 因 `paths-ignore` 不触发）；三者皆绿仍为「CI 全绿」的**信号口径**，但**不再阻断合并**。红线由门禁层 `CI Gate` 承担。

### 5.5 已知代价（显式声明、由真人承担）

1. **非 docs PR 上三件套仍产 check，但红了不再阻断合并** —— 门禁红线**只有** `CI Gate`。
2. **docs-only PR 上三件套连信号都没有**（workflow 被 `paths-ignore` 跳过）—— 文档 PR 的 CI 信号 = 仅 `CI Gate`。
3. **`CI Gate` 是活性令牌**，**不校验任何实质内容** ⇒ 门禁强度实质 = 「PR 的 workflow 能跑起来」。性质 = **以门禁强度换 docs CI 成本的显式取舍**（真人已知情承接）。

## 6. 落地形态

### 6.1 新增 gate workflow（骨架）

新增文件 `.github/workflows/ci-gate.yml`。**关键：`jobs.gate.name` 必须逐字 `CI Gate`**（required status check 的 context 取 **job 名**，而非 workflow 名）——不得省略 `name:`。

```yaml
name: CI Gate
on:
  pull_request:
  push:
    branches: [main]
jobs:
  gate:
    name: CI Gate
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - name: Always-run required gate
        run: |
          echo "required gate: always reports"
```

**要点**：

- **无 `paths` / `paths-ignore` / `if` / `needs`** —— 自包含，**恒定上报**；不落 E6 的「上游被跳过 ⇒ 下游静默绿 / 死锁」陷阱。
- **触发面 = `on: pull_request`（全 PR，无过滤）+ `on: push: branches: [main]`** —— 覆盖 PR 门禁与 `main` 落地两条路径。
- **`integration_id`**：ruleset 目标态指定 `integration_id=15368`（GitHub Actions app），与三件套同 app（E7 / E8 同为 15368）；context 名 `CI Gate` 与 job 名一致。
- **`timeout-minutes: 5`** —— 轻量 job 兜底，恒定增量极小。
- **不引入 secrets / 权限** —— 无需 `permissions` 段（默认只读即可 `echo`）。

### 6.2 ruleset 目标态（配置，非文件）

对 ruleset `23927156`（`main-protection-ci-gates`）执行 `PUT`，仅改 `rules[required_status_checks]` 的 `required_status_checks` 子项：

```json
{
  "required_status_checks": [
    { "context": "CI Gate", "integration_id": 15368 }
  ],
  "strict_required_status_checks_policy": true
}
```

**不变项**：`enforcement=active`、`conditions.ref_name.include=["~DEFAULT_BRANCH"]`、`deletion`、`non_fast_forward`、`pull_request`（含 `required_approving_review_count=0` 等全部子项）、`bypass_actors=[]`。**该动作需 `admin` 权限**——agent token 无 `admin:org` ⇒ **大概率由真人执行**（真人已授权）。

### 6.3 唯一安全执行序列（步1–步5）

> **Q4 死锁在 C3 下不成立**：下文步1 的 PR-1 不含任何被 `paths-ignore` 匹配的路径 ⇒ 旧三件套照常上报 ⇒ PR-1 **自身可合入**；故「改 required 需先注册新 check」的顺序死锁被本序列的一次性「先注册、后收窄」规避。

| 步 | 动作 | 过闸理由 | 归属 |
|---|---|---|---|
| **步1** | **PR-1（非 docs）**：新增 `.github/workflows/ci-gate.yml` | 改动不含任何被 `paths-ignore` 匹配的路径 ⇒ `ci.yml` 与 `qodana_code_quality.yml` **均实跑** ⇒ `Build & Test (JDK 21)`、`Qodana for JVM` 上报；`image-guard` 因 changes 白名单不含该新文件 ⇒ `if` 跳过 ⇒ 报 **Success**（E6：job 因 conditional 跳过 ⇒ Success）⇒ **旧三件套全上报 ⇒ PR-1 可合入**。此步同时让 `CI Gate` **首次出现并成功** | build 票（PR-1） |
| **步2** | **合并 PR-1** | `CI Gate` 已注册，此后**每个 PR 必上报** | 真人 / 合入 |
| **步3** | **ruleset `PUT`** | required 收窄为单条 `CI Gate`；当前**无 open PR** ⇒ **不追溯阻断存量** | 真人（agent 无 `admin:org`） |
| **步4** | **PR-2（docs）**：改规范 0008 的 §7（AC 行）与 §1（口径行）为 v3 门禁层 / 信号层口径 + 补 `docs/architecture.md` 规范索引（补 0008、0009） | docs-only ⇒ 旧三件套**缺席**，但**它们已不在 required**；唯一 required `CI Gate` **无过滤 ⇒ 实跑 success** ⇒ 可合入 | build 票（PR-2） |
| **步5** | **trail 实测（验收用，非决策前置）**：分支 `test/99-docs-only-trail`（基于步3 后的 `main`），纯文档改动 | 预期 `CI Gate`=success 且三件套**缺席**、PR 可合入；**无论成败均「关 PR、不合并、删分支」⇒ 零残留**，**失败本身即结论**（回退 C4 备选，见 §11） | build 票（验收） |

## 7. 验收标准（AC）

> 全部为**可判定**条目；对应 #99 的 AC①–④ 定稿。实现由 build 票承担，本规范只定义验收线。

- [ ] **AC①（required 集合精确）**：`GET /repos/luochenfx/ecom-flowcart/rulesets/23927156` 的 `required_status_checks.required_status_checks` **精确等于**单条 `CI Gate`（`context="CI Gate"`、`integration_id=15368`），无其它 context 残留。
- [ ] **AC②（旧三件套退出 required）**：`Image Build Guard` 与 `Qodana for JVM`（及 `Build & Test (JDK 21)`）**不再作为 required context**；其 workflow 与 job 定义**未被删除或改动**。
- [ ] **AC③（死锁消除，可判定）**：一条 **docs-only** trail PR 上 `CI Gate` **上报 success** 且该 PR **可合入**；另有「一条**故意让 `CI Gate` 红**」的 PR **无法合入**。二者须以实测记录（run id + `gh pr checks` 输出）回填本规范。
- [ ] **AC④（未误伤）**：non-docs PR 的 `Build & Test` / `Qodana for JVM` 仍**照常运行**、`image-guard` 行为**不变**（`paths-ignore` 与 job 级 `if` 原样）；agent 经 PR 合并不直推 `main`。
- [ ] **AC⑤（口径落地）**：规范 0008 §7 / §1 的「CI 绿」口径已改写为 **v3（门禁层 / 信号层）**（步4）。
- [ ] **AC⑥（索引同步）**：`docs/architecture.md` 规范索引补入 **0008** 与 **0009**（步4）。
- [ ] **AC⑦（零残留）**：步5 trail 分支已删除、trail PR 已关闭未合并；`CI Gate` 之外无遗留新增分支 / PR / ruleset 变更。

## 8. 唯一安全执行序列（详述）

见 §6.3。此处补充两点纪律：

1. **顺序不可交换**：**步3（收窄 required）必须晚于步2（`CI Gate` 已注册成功）**。若提前收窄，`CI Gate` 尚未在该 ruleset 语境下成功过 ⇒ 触发 E6「required check 须近 7 天内成功完成过」的资格问题 / 死锁。
2. **步5 是验收、不是决策前置**：步5 失败**不推翻** C3-β 的决策地位，而是**回退触发条件**（见 §11 fallback）。步5 必须在**步3 之后**的 `main` 上开分支，否则测的是旧态。

## 9. 文件级修改清单（相对路径）

| 类别 | 文件 | 变更 | 归属步骤 |
|---|---|---|---|
| **本 Phase 实际写入（新增）** | `docs/specs/0009-ci-required-status-gate.md` | 新建本规范 | Phase 5（本轮） |
| **后续待增（非本 Phase）** | `.github/workflows/ci-gate.yml` | 新增 always-run gate workflow（§6.1 骨架，`jobs.gate.name` 逐字 `CI Gate`） | 步1（PR-1） |
| **后续待改（非本 Phase）** | `docs/specs/0008-ci-image-build-guard.md` | §1 口径行 + §7 AC 行的「CI 绿」口径改写为 v3（门禁层 / 信号层） | 步4（PR-2） |
| **后续待改（非本 Phase）** | `docs/architecture.md` | 「规范索引」补入 0008 与 0009（当前仍写 `0001–0007`，见 E10） | 步4（PR-2） |
| **配置变更（非文件）** | ruleset `23927156` | `required_status_checks` 收窄为单条 `CI Gate`（§6.2）；其余规则 / `bypass_actors` 不变 | 步3（真人 `PUT`） |
| **不改** | `.github/workflows/ci.yml` | 无（`paths-ignore` 与 `image-guard` 的 `if` 原样保留） | —— |
| **不改** | `.github/workflows/qodana_code_quality.yml` | 无（`paths-ignore` 原样保留） | —— |

**数据清理范围**：无。**表结构变更与迁移脚本**：无。

## 10. 风险与残留未知

| 编号 | 项 | 分级 | 处置 |
|---|---|---|---|
| **R1** | **门禁强度实质下降**：`CI Gate` 为活性令牌，不校验任何实质内容（§5.5 代价 3） | `non-blocking`（真人已知情） | 显式登记；若日后要求更强门禁 ⇒ 回退 C4（见 §11） |
| **R2** | **非 docs PR 三件套红了不阻合并**（§5.5 代价 1） | `non-blocking`（真人已知情） | 显式登记；信号口径仍在（三件套照跑） |
| **R3** | **docs-only PR 无三件套信号**（§5.5 代价 2） | `non-blocking` | 显式登记；文档 PR 的 CI 信号 = 仅 `CI Gate` |
| **R4** | **#94-①「守门不可自守删除自己」**（filters 读自**被测 PR 自身检出** ⇒ 删掉白名单那行的 PR 自身不被 `image-guard` 覆盖） | `non-blocking`（**本命题外**） | 在 C3 下**严格弱于 C4**（`Image Build Guard` 连 required 都不是）⇒ **另开独立 build 票**，设计一个**不读 PR 自身检出**的检查（例如经 REST 读 PR diff 判定白名单行是否被删）。**本规范不顺手做**（见 §13） |
| **R5** | **步4 对 0008 的口径改写未落地前，0008 §7 的旧口径与 v3 并存** | `non-blocking` | 步4 落地后消除；期间以本规范 §5.4 为**权威口径**（v3） |
| **R6** | **规则执行者权限**：步3 ruleset `PUT` 需 `admin`，agent token 无 `admin:org` | `non-blocking` | 由**真人执行**（真人已授权）；agent 只核对 AC① |
| **R7** | **`docs/architecture.md` 规范索引陈旧**（E10：仍 `0001–0007`） | `non-blocking` | 步4 一并补齐（0008 + 0009） |
| **R8** | **明确不建议**在步3 顺手启用 `require_code_owner_review`（E11：本仓无 CODEOWNERS 文件；且单人 owner ⇒ PR 作者即唯一 code owner、无法自审 ⇒ 会引入「所有 PR 永久待审」的**新版死锁，与本次所修问题同形**） | `non-blocking`（**否决建议**） | **不启用**；若坚持启用，前置条件 = 先加 `.github/CODEOWNERS` + 存在**第二审批人** |

**blocking 未知点：0。**

## 11. 回滚 / 可否决与契约影响

- **回滚（按粒度排列）**：
  1. **最小回滚（ruleset 级）**：把 ruleset `23927156` 的 `required_status_checks.required_status_checks` **恢复为旧三件套**（`Build & Test (JDK 21)` / `Image Build Guard` / `Qodana for JVM`）。效果 = 回到 #99 前状态（docs-only PR 死锁**复现**）；`CI Gate` workflow 保留无害（仍上报，只是不再 required）。
  2. **完全回滚（加文件）**：在步1 之上**删除** `.github/workflows/ci-gate.yml`（须与步1 的 ruleset 收窄**成对撤销**；单独删 gate 而 required 仍指 `CI Gate` ⇒ 所有 PR 永久 pending 的新死锁）。
  3. **回退到 C4（备选升级路径）**：若步5 trail 实测**失败**（或日后要求更高门禁强度），**切换 C4** —— 移除 `ci.yml` 与 `qodana_code_quality.yml` 的 `on.pull_request.paths-ignore`（C4 机制无损、始终可回退），并把 required 恢复为三件套。**代价 = 真人已明确不接受的全量 CI 成本**（E3 / E4）。
- **真人否决权**：真人可随时否决本决策；若改选其他形态（含 C4），须重出 DecisionRecord。
- **契约影响声明**：**零** `core-contracts` 字段 / 方法签名变更，不触 `AGENTS.md`「core 改动即中止」闸门。本规范改动面在 **CI 配置（ruleset）+ workflow 文件 + 文档**，与业务模块契约无关。

## 12. 需同步的文档（建议，本轮不写）

| 文档 | 建议变更 |
|---|---|
| 规范 0008 §7 / §1 | 「CI 绿」口径改写为 v3（门禁层 / 信号层）——属**步4（PR-2）** |
| `docs/architecture.md`「规范索引」 | 补入 0008 与 0009（当前 `0001–0007` 陈旧）——属**步4（PR-2）** |
| `README.md` | 如需说明「CI 绿」= 门禁层 `CI Gate` + 信号层三件套，可同步（**建议，非必须**） |

（上述除步4 两项外均属文档票范围，本规范仅**提出建议**，不代替写。）

## 13. 明确不做的事（范围外）

以下问题**本规范不解决**，实现时若遇到**不要顺手做**：

| 项 | 归属 |
|---|---|
| 三件套 workflow 的 `paths-ignore` 治理（C4 机制） | **备选**（§11 回退路径），本轮**不做** |
| #94-①「守门不可自守删除自己」（`image-guard` 白名单删除自身 / `ci.yml` YAML 语法失效致 workflow 不触发） | **另开独立 build 票**（设计不读 PR 自身检出的检查）。本规范只登记（§10 R4） |
| 启用 `require_code_owner_review` | **明确不建议**（§10 R8）；本规范不做 |
| 媒体根未接线缺陷（`app.media-root` 未被 app 代码消费 / compose `app` 服务未设 `FLOWCART_MEDIA_ROOT`） | 规范 0008 §10 R3 已登记，**另立票**；本规范不触碰 |
| CI runner 成本治理 | 后续 |
| 设计期文档（README / architecture）除步4 两项外的改写 | 文档票 |
