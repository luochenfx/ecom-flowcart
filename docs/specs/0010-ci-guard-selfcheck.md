# 规范：CI 守门自守检查（不读被测 PR 自身检出的白名单下界断言）

> 来源：GitHub issue [#103『Build: 守门不可自守删除自己——设计不读 PR 自身检出的检查』](https://github.com/luochenfx/ecom-flowcart/issues/103)（label `ready-for-agent`）
> 依赖：[规范 0008（CI 镜像构建守门）](./0008-ci-image-build-guard.md) §10 R2（残余边界 ①「删除自己」/ ②「语法级失效」）、§11（回滚路径 2「白名单行删除」）、§13；[规范 0009（CI required status check 门禁）](./0009-ci-required-status-gate.md) §10 R4、§13（均声明本项**另开独立 build 票**）
> 状态：**v1.4（实现期 + 第 4 轮审查修复）** —— 落地自守 workflow 本体 + 本规范。第 1 轮审查后：锚定面改为**消费链**、**显式**安装 PyYAML、收窄权限、补自守边界 B8、补三段式（正向绿→删行红→语法坏红）trail 计划、补 `0009` §10 R4 一行回指。**第 2 轮审查后**：锚定基座改为 **base 侧守门链** + head 侧「不削弱」断言（闭合 A5/A5p「消费点搬到任意含基线的 job」与 A16/A17「给守门链加恒假 `if`」两类**静态结构完好、运行期覆盖已死**的假绿）、正则**兼容括号下标语法**（修 A8 假红）、**修正 B8/§13 R1 被证伪的表述**、新增 **B9/B10** 与**威胁模型边界**段落（D1-B）。**第 3 轮审查后**：把「不削弱」断言**对称扩展到消费节点**（消费 job = 门禁本体 `image-guard`），闭合 **A23a/b/c、A24b、A24c、A27、A28、A30** 等消费侧假绿；`if` 比较改为**空白 + 下标语法归一**（修 A33）；`locate_base_chains` 跳过 base 侧「产出非 dorny」的无关 job（修潜伏恒红 D4）；A25 登记为 B 类边界。**第 4 轮审查后（本版）**：按主理人裁决把判据从「逐属性打地鼠」升级为**可枚举的封闭字段集合**（**执行性**：job/step 的 `if` / `needs` / `strategy` / `continue-on-error` / `uses`（job 另含 `concurrency`）；**输出接线**：产出 job `outputs.<K>` 逐键值、产出 step `with` 的非 `filters` 键），补**产出 job `outputs.<K>` 接线断言**（闭合 **X1–X4**）与**产出 step `continue-on-error` / `with` 断言**（闭合 **X8**），并给出**封闭性论证**（§6.2.1）；**收窄 §8.2/§9.1/§13 R8 的「一律红」过度声称**、**修正 AC1 为「部分闭合 / 已枚举构造」**、新增 **B12**、登记 **step 内容 / `uses` 版本面**。trail 自举实测须待 workflow 合并入 base 后执行（见 §8 时序依赖）。**v1.5（本版：回填线上自举取证 + P0 事故归档）**：三段式线上 trail 已完成并按实测回填（§8.3）；新增 **§10.1** 归档「workflow 被判 Invalid ⇒ 检查从未运行」的 P0 事故、记录新增的**静态护栏**（`run` 正文表达式机检，见 §10.1 ⑤）与 **R10**；AC1/AC2/AC5 更新为实测结论。**全文为与护栏口径一致，凡 GitHub 表达式一律以「`$` + 双花括号」转写，不写原始字面量。**
> **v1.6（本版：issue #116 触发面加固交付规范）** —— 来源：issue [#116『Build: 自守检查触发面加固——on.paths / paths-ignore / types 三种写法须全覆盖』](https://github.com/luochenfx/ecom-flowcart/issues/116)，据已收敛决策记录 **DR-116-01**（决策日志 `.decisions/116-guard-trigger-surface-hardening-decision-log.md`）定稿。本版：① 把「**触发面**」登记为 §6.2.1 封闭字段集合的**第三类**（官方 `pull_request_target` 过滤键恰 5 个且可枚举 ⇒ 与既有两类同型）；② 新增 **§8.4**（#116 可执行交付规范：AC 清单 / 硬约束 HC-1–HC-10 / 判定口径 / 四段式线上取证 / 残留 R-A·R-B·R-C / 可否决点 / 回滚）；③ §9 新增 **B13**；④ 改写 **§13 R9** 并新增 **R11–R13**；⑤ 更新 **§3.1 Out** 与 **§16** 的 follow-up 票号；⑥ §12 增列本轮写面。**措辞红线（本版强制）**：本版只声称「**PR 阶段必红已闭合**」；「**阻止生效**」与「**合并后删掉/改坏本断言**」属**登记残留**（R-A / R-B），**不得**声称「已闭合触发面」「已可阻止其生效」「已闭合 B2」。
> 范围声明：本规范**只**解决「镜像守门的路径白名单**不再自守自己**」这一命题（#94-① 残余边界）。**不改** mirror 守门的判据与运行时契约断言（AC3），**不设/不改任何 required check**（归 #104），**不碰** `docs/architecture.md`（归 #105）。详见 §3 与 §16。

## 1. 决策概览

- **问题一句话**：`ci.yml` 的 `image-guard` 路径白名单由 `dorny/paths-filter` 从**被测 PR 自身的检出**读取 ⇒ 某 PR 只要删掉白名单里的自守条目 `- '.github/workflows/ci.yml'`，该 PR 自身就不再被 `image-guard` 覆盖（且按官方语义「job 被 `if` 跳过 = Success」）⇒ **删除动作本体零 CI 证据**（0008 §10 R2 ①）。让 `ci.yml` YAML 语法失效则 workflow 整体不触发，同样零证据（残余边界 ②）。
- **采纳方案：新增独立自守 workflow（`pull_request_target` + REST 读 head，语义锚定下界断言）**。
  1. **新增** `.github/workflows/guard-selfcheck.yml`：触发用 **`pull_request_target`**（workflow 定义读自 **base/默认分支** ⇒ PR 改不动检查本体）；
  2. **判定不读被测 PR 的检出**：全程**不 `actions/checkout`**、不执行任何 PR 代码，只经 **GitHub REST API** 读 **`base_sha`（可信锚）与 `head_sha`（被测）两侧**的 `ci.yml`：由 base 侧解出守门链身份，再对 head 侧做「不削弱」断言（§6.2）；
  3. 同一 job 内附**自守断言**：本 workflow 文件在 head 侧仍须存在（防「删除/改名守门本体」，AC4 的机制化）。
- **语义锚定（Q1）**：**以 base 版 `ci.yml` 为可信锚**解出「守门链身份」——由下游 job 的 `needs.<j>.outputs.<k>` 反查「**真正驱动门禁**」的那条 `dorny/paths-filter` 步骤（步骤 id 由该 job 的 `outputs.<k>` 解出，其值 = 「`$` + 双花括号」包裹 `steps.<id>.outputs.*`），再取该步骤 `filters.image` 的条目集合；**同时**记录**消费节点身份**（`if` 中引用 `needs.<j>.outputs.<k>` 的 job 本体，本仓 = `image-guard`）。据此对 head 侧做「不削弱」断言（**第 4 轮起 = 逐字段相等（封闭集合）**）—— **执行性字段**（产出 / 消费 job·step 的 `if` / `needs` / `strategy` / `continue-on-error` / `uses`，job 另含 `concurrency`）+ **输出接线**（产出 job `outputs.<k>` 逐键值、产出 step `with` 的非 `filters` 键、产出 step `filters.image` ⊇ **base 基线 ∪ 硬基线**）+ **消费节点完整性**（消费点 `needs.<j>.outputs.<k>` 未被搬走、消费 job 本体仍在、其执行性字段与各 step 执行性字段逐条与 base 相等）；**不按**「首个同名 action」、**不按** job / step 名、**更不按**行号（行号易腐，承 0008 §11 注）。见 §6.2。
- **判据 / 被测解耦**：**锚来自 base 侧**（本 workflow 硬编码的基线 + **base 版 `ci.yml`** 解出的守门链身份；`base_sha` = `github.event.pull_request.base.sha`，base 内容 PR 不可篡改），observed 来自 head 侧 REST 读 ⇒ 消除「判据随 PR 检出漂移」的根因。
- **AC3 零触碰**：本票**不改** `ci.yml` 的 `changes` job 判据（`filters.image` 条目集合）与 `image-guard` 的 `Assert runtime contract (non-root + /data writable)` step 一字。
- **AC4 自守边界**已在 §9 逐条列清；**Q2 假红处置**见 §10；**Q4 顺序死锁**见 §11。
- **零契约变更**：不触 `core-contracts`，不触发 `AGENTS.md`「core 改动即中止」闸门。
- **一行不越界**：本票**新增 1 个 workflow + 1 个 spec 文档**，并在 `0009` §10 R4 追加 **1 行回指**（第 1 轮 D3）；写动作前 pre-flight-check，范围蔓延即上报（见 §12 / §16）。

## 2. 来源与关系（红线）

| 载体 | 与本规范的关系 | 红线 |
|---|---|---|
| **#103**（本规范命题载体） | 登记「守门不可自守删除自己」的残余边界；其 AC1–AC4 由本规范细化定稿（§7） | —— |
| **规范 0008**（依赖） | 定义 `changes` + `image-guard` 两 job、白名单 6 条（含自守条目）、「CI 绿」口径；其 §10 R2 登记本残余边界、§11 定义「白名单行删除」为**合法回滚路径 2** | **不改** 0008 正文（0008 §7/§1 的口径改写属 #100 步4 / #104；本票不动，避免撞车） |
| **规范 0009**（依赖） | §10 R4 把本项登记为「另开独立 build 票，设计不读 PR 自身检出的检查」；§13 声明范围外 | 0009 正文**不改**；**唯一例外**：§10 R4 追加 **1 行**回指本项已由 0010 落地（本票第 1 轮 D3），不动其它段落 |
| **ruleset `23927156`** | `required_status_checks` 目标构成归 **#104（真人执行）** | 本票**不设/不改**任何 required check |
| **PR #97 / #94** | 「守门自证」触达面（白名单含工作流自身）的一手实测来源 | 其残余边界即本票；本票**不重开**触达面 |

**边界一句话**：本票处理的是**「守门自己的白名单不再自守自己」**这一自指缺口，**不是**白名单触达面（#94，已闭合）、也**不是** required 构成（#104）。

## 3. 命题与范围

### 3.1 命题（in）

| 维度 | 内容 |
|---|---|
| 命题 | 如何加一条**判定不依赖被测 PR 自身检出**的检查，使「删掉白名单自守条目（或改坏 `ci.yml` 语法）」的 PR **必然拿到红色的 CI 证据** |
| In | 一条自守 workflow 的**触发机制选型**、**判定证据源**、**锚定面**、**自守边界**与**回滚路径** |
| Out | mirror 守门判据 / 运行时契约断言的任何改动（AC3）；required 构成（#104，已 CLOSED）；`docs/architecture.md`（#105）；镜像内容架构。**触发面治理（v1.6 更新；按判据主体分两侧）**：**(2a) 本检查自身触发面**（`guard-selfcheck.yml` 的 `on.pull_request_target` 的 `paths` / `paths-ignore` / `types` 等过滤键）—— 其「**PR 阶段报红**」一侧**已由 #116 闭合**（本规范 **§8.4**，交付物 = 在 `guard-selfcheck.yml` 内新增一条**对 head 侧自身 `on` 块**的断言 step），其「**阻止生效**」一侧属登记残留 **R-A**（非 required ⇒ 带红仍可合入）；**(2b) 其它 workflow 的触发面**（`ci.yml` 的 `paths-ignore`（C4，0009 备选）与 **`on.pull_request.paths` 正向过滤（A25）**）—— **#116 不覆盖**（判据主体是另一文件，#116 的新断言**不读** `ci.yml` 的 `on` 块），维持「**B 类边界 + 另立票**」，归 **#119（架构 / 配置面）或另立 follow-up 票**；见 §6.2.1 末「范围澄清」/ §9.1 边界② / §13 R9 |

### 3.2 收敛状态

- blocking 未知点 **0**；机制候选集覆盖「可覆盖语法失效分支」的完整谱系（`pull_request_target` / `workflow_run` / ruleset `workflows` rule / 常规 `pull_request`），裁定唯一；本票**不涉及 required**，故不触发 0009 §6.3 的顺序死锁（§11）。

## 4. 现状与证据

### 4.1 缺口现状

| 环节 | 事实 | 后果 |
|---|---|---|
| 白名单来源 | `changes` job 的 `Filter image-related paths` step 用 `dorny/paths-filter@v3`，其 `filters.image` 由 action **从被测 PR 自身的检出读取**（0008 §10 R2 ①一手证据：同 run 的 dorny 日志回显 6 条，而 base `main` 当时仅 5 条） | PR 可改「判据」本身 |
| 删除自守条目 | PR 删掉 `- '.github/workflows/ci.yml'` ⇒ 该 PR 的 `ci.yml` 改动不再匹配白名单 ⇒ `image-guard` 的 `if: needs.changes.outputs.image == 'true'` 为假 ⇒ **job 被跳过 = 报 Success**（官方语义 E5） | 删除动作**零证据** |
| 语法级失效 | 改坏 `ci.yml` YAML ⇒ workflow 整体不触发 ⇒ 关联 check 停 Pending（若 required）或干脆不产生 | 同样**零证据** |
| 现有兜底 | 本仓 `main` 的经典分支保护为空（§4.2 E2），保护由 ruleset 承担；ruleset 内**无**针对「白名单被删」的规则 | 二者只能靠人工 review 兜底 |

### 4.2 证据台账（一手）

| 编号 | 引用 | 得到的结论 | 可信度 |
|---|---|---|---|
| E1 | `GET /repos/luochenfx/ecom-flowcart/rulesets/23927156`（实测） | `enforcement=active`、`conditions.ref_name.include=["~DEFAULT_BRANCH"]`；rules = `deletion` / `non_fast_forward` / `pull_request` / `required_status_checks`；**`required_status_checks.required_status_checks=[{"context":"CI Gate","integration_id":15368}]`**、`strict_required_status_checks_policy=true`、`bypass_actors=[]`、`current_user_can_bypass="never"`。**注**：任务卡 1.5 记「required 当前为 `[]`（过渡态）」，实测已为单条 `CI Gate`（#100/步3 似已执行）——本票据此复算，**不采信任务卡快照** | 高（一手 API） |
| E2 | `GET /repos/luochenfx/ecom-flowcart/branches/main/protection`（实测） | 恒 **404 `Branch not protected`** ⇒ 保护**全部由 ruleset 承担** | 高（一手 API） |
| E3 | `.github/workflows/ci.yml`（实测） | `changes` job 的 `dorny/paths-filter@v3` step，`filters.image` **共 6 条**，第 6 条逐字为 `.github/workflows/ci.yml` | 高（一手文件） |
| E4 | GitHub Docs《Events that trigger workflows》`pull_request_target` | 见 §5.2 逐字引用 | 高（官方文档） |
| E5 | GitHub Docs《Troubleshooting required status checks》 | 见 §5.2 / §11 逐字引用 | 高（官方文档） |
| E6 | 本票**本地自举单测**（第 2 轮：**29 场景**，`subprocess.run` 打桩喂入 **base + head 两侧** fixtures；第 1 轮为 11 场景） | 正向绿→pass；纯删行 / 诱饵遮蔽 / 结构不可识别→fail；**消费点转移（活跃 / 禁用）→fail**；**守门链加恒假 `if`→fail**；**下标语法 / 复合条件 / 无关新增 / 扩项→pass**；守门文件被删→fail。见 §8.2 | 高（本地实测） |

> **行号锚定注（承 0008 §11 / 0009 §4.3）**：本规范**不以 `ci.yml` 行号**作判定或文档锚点，一律按 **action 名 / job / step 名称 / YAML 解析后的语义结构**定位。本 workflow 的判据同样**不读行号**。

## 5. 决策结论与被拒候选

### 5.1 选定：`pull_request_target` + REST 读 base/head + 守门链「不削弱」断言

| 准则 | 结论 |
|---|---|
| 抗篡改 | `pull_request_target` 的 workflow 定义读自 **base/默认分支**（E4 逐字）⇒ PR **无法**改本检查本体；且本检查**不 checkout / 不执行** PR 代码，从根上避开「PR 修改检查逻辑」的手法 |
| 反自指 | 锚来自 **base 侧**（本 workflow 硬编码的基线 + **base 版 `ci.yml`** 解出的守门链身份；base 内容 PR 不可篡改），observed 经 **REST 读 `head_sha`** ⇒ 「判据」与「被测检出」解耦 |
| 覆盖语法失效 | observed 由 **REST 直接取原始字节**，与 `ci.yml` 是否可被 GitHub 解析**无关** ⇒ 外层 YAML 坏掉也能读到并判红（E6：A11 场景） |
| required 资格（前瞻） | `pull_request_target` 属**可为 required check 产出可评估 check** 的合规事件（E5 逐字清单含它）⇒ 若 #104 日后纳入 required，无事件资格障碍（§11） |
| 成本 | 单 job、`timeout-minutes: 5`、只读权限、无 checkout / 无镜像构建 ⇒ PR 增量可忽略 |
| 可逆性 | 撤销 = 删除本 workflow 文件（§14）；不触碰既有守门与 required |

### 5.2 一手依据（逐字）

**`pull_request_target`（E4，[来源](https://docs.github.com/en/actions/reference/events-that-trigger-workflows#pull_request_target)）**：

> "This event runs in the context of the default branch of the base repository, rather than in the context of the merge commit, as the `pull_request` event does. This prevents execution of unsafe code from the head of the pull request that could alter your repository or steal any secrets you use in your workflow."

> "By default, a workflow only runs when a `pull_request_target` event's activity type is `opened`, `synchronize`, or `reopened`."

> 事件表：`GITHUB_SHA`＝"Last commit on default branch"；`GITHUB_REF`＝"Default branch"。

> "Running untrusted code on the `pull_request_target` trigger may lead to security vulnerabilities." （⇒ 本 workflow **只读权限 + 不 checkout / 不执行 PR 代码**，规避该安全面）

**required status checks（E5，[来源](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)）**：

> "A required status check must have completed successfully in the chosen repository during the past seven days."

> "Successful check statuses are `success`, `skipped`, and `neutral`."

> "For checks created by workflow jobs to be evaluated for a pull request, the workflow run must be triggered by one of these events: `push` `pull_request` `pull_request_review` `pull_request_target` `deployment` `deployment_status`."

> "If a workflow is skipped due to path filtering, branch filtering or a commit message, then checks associated with that workflow will remain in a 'Pending' state... If a job in a workflow is skipped due to a conditional, it will report its status as 'Success'."

**skip 语义（[来源](https://docs.github.com/en/enterprise-server@3.16/actions/how-tos/manage-workflow-runs/skip-workflow-runs)）**：

> "adding `[skip ci]` to a commit message won't stop a workflow that's triggered `on: pull_request_target` from running." （⇒ PR **无法**用 commit message 跳过本检查，自守性 +1）

### 5.3 被拒候选（保留理由与依据）

| 候选 | 一句话形态 | 拒因 | 依据 |
|---|---|---|---|
| **常规 `pull_request`** | 用普通 PR 事件触发自守检查 | workflow 定义读自 **head/merge commit** ⇒ PR **可删掉/改坏本检查自身**来让其失效（与待修缺口**同形**）⇒ 自指。与 `pull_request_target` 的官方语义差异（E4 逐字）恰是本候选出局的直接依据 | E4 |
| **`workflow_run`** | 挂在 `ci.yml` 完成之后跑 | ① 若 `ci.yml` **YAML 语法坏** ⇒ 上游 `ci.yml` 不触发 ⇒ 无 run ⇒ `workflow_run` **不触发** ⇒ **覆盖不了 AC2 的语法失效分支**；② `workflow_run` **不在**「可为 required check 产出可评估 check」的合规事件清单内 ⇒ 若日后纳入 required 将**不满足**资格 | E5 |
| **ruleset `workflows` rule** | 用 ruleset 的 `workflows` 规则强制某 workflow 必须运行 | 配置需 **admin 权限**（真人执行）；且它是**配置**而非「文件本体」，agent 无法在本票内落地 ⇒ 最多作为**未来加固**登记（§14 / §16），不作主选 | 任务卡 1.5 / 0009 §6.2（agent token 无 `admin:org`） |
| **靠 code review / 人工兜底** | 不做机器检查，全靠人审 | 正是**当前态**（§4.1 末行）⇒ 未解决「零证据」命题；E2 显示无分支保护机器兜底 | E2 |

## 6. 落地形态

### 6.1 新增自守 workflow（骨架）

新增文件 `.github/workflows/guard-selfcheck.yml`（与既有 `ci.yml` / `ci-gate.yml` / `qodana_code_quality.yml` **不冲突**）。要点：

- `on.pull_request_target.types: [opened, synchronize, reopened]`（默认三型，显式书写）；
- `permissions: {contents: read}`（最小权限；判定只读文件内容，不经任何 pull-requests 端点 ⇒ 不授 `pull-requests: read`）；
- 单 job（`name: Guard Self-Check`），**三步**：① `actions/setup-python@v5`（固定 `python-version: "3.12"`）→ ② `pip install --quiet "pyyaml==6.0.3"`（**显式**获取判定依赖，消除「隐式依赖 runner 自带 PyYAML」的假设）→ ③ bash → `python3`（判定脚本）。无 `paths` / `paths-ignore` / `if` / `needs`（恒定上报）；判定步骤经 `env` 注入 `PR_HEAD_SHA` 与 `PR_BASE_SHA`。
- **不 `actions/checkout`**；判定全程经 `gh api`（用 `GITHUB_TOKEN`）读 **base / head 两侧**文件（base 作**可信锚**，head 为**被测**）。

### 6.2 判定锚定面（Q1）—— base 侧守门链 + head 侧「不削弱」断言

**锚定基座 = base 侧 `ci.yml`（可信锚，PR 不可篡改）**；**判定 = 对 head 侧做「不削弱」断言 —— 判据 = 逐字段相等（封闭集合，见 §6.2.1）**：**产出侧执行性 (a)(a')(c) + 条目下界 (b) + 输出接线 (e) + 消费节点完整性 (d)/(d1)–(d3)**。

**第一步（base 侧解链，读 `base_sha` 的 `ci.yml`）**——按**消费链语义结构**解出「守门链身份」（本仓 = 产出侧 `changes`/`filter`，消费侧 `image-guard`）：

1. **消费点锚**：扫描 base `ci.yml` 各 job 的 `if`，收集形如 `needs.<J>.outputs.<K>` 的引用 ⇒「被消费的 job 输出」即门禁消费点，**引用它的 job 即消费节点**（本仓 = `image-guard` 的 `needs.changes.outputs.image`）；
2. **产出步锚**：由 `<J>.outputs.<K>` 的值（「`$` + 双花括号」包裹 `steps.<ID>.outputs.*`）解出**产出步骤 id**（本仓 = `changes` 的 `filter`）——**不按 job / step 名、不按行号**；
3. **输入锚**：该产出步须以 `dorny/paths-filter` 为 action；读其 `with.filters`（内嵌 YAML 字符串），`safe_load` 后取 `image` 键列表 ⇒ **`BASE_ENTRIES`**；
4. **身份落库**：产出侧记 `{producer_job, output_key, step_id, step_uses, entries, producer_exec, producer_outputs, producer_step_exec, producer_with_other}`（`producer_exec` / `producer_step_exec` = 产出 job / step 的**执行性字段签名**；`producer_outputs` = 产出 job `outputs` 逐键归一值；`producer_with_other` = 产出 step `with` 的非 `filters` 键归一值）；消费侧逐条记 `{job_key, exec, step_execs}`（`exec` = 消费 job 执行性签名；`step_execs` = 每个 step 的「身份（`name` 优先、无 `name` 用 `uses` 兜底）→ 执行性签名」）。

> **D4（base 识别不过宽）**：base 是**可信锚**，故 `locate_base_chains` 仅对「产出**确为 `dorny/paths-filter`**」的引用建链；对 base 侧「产出非 dorny / 产出不可解」的**无关 job**（如未来某 job 因别的原因引用 `needs.X.outputs.Y` 而 X 非 dorny）**跳过**——避免 base 一旦新增此类 job 令 `locate_base_chains` 返 `None` ⇒ **所有 PR 恒红**（潜伏失效）。仅当跳过全部候选后**无任何合法链**时才 fail-closed 红（A22）。

**第二步（head 侧「不削弱」断言，逐条；任一不成立即红）**——读 `head_sha` 的 `ci.yml`：

| 断言 | 内容（**逐字段相等**，归一后） | 因本断言闭合的反例 |
|---|---|---|
| **(a)** 产出结构 | 同 job key `<J>` 存在、同 step id `<ID>` **存在且唯一**、该 step 仍 `uses: dorny/paths-filter*` | 产出链删除 / id 重复 / 换成非 dorny（A6/A6b/A7/A15） |
| **(b)** 条目 | 该 step 的 `filters.image` ⊇ **`BASE_ENTRIES` ∪ 硬基线**（不得删除/弱化 base 已承诺的条目；**允许扩项**） | 删 / 弱化白名单条目（A1/A2/A3/A5q） |
| **(c)** 产出 job **执行性字段** | 产出 job 的 `if` / `needs` / `strategy` / `continue-on-error` / `uses` / `concurrency` 与 base **逐字段相等** | 给产出链加恒假 `if` / `continue-on-error` / 空 `strategy` / 改 `needs`（A16/A38、X7 类） |
| **(a')** 产出 step **执行性字段** | 产出 step 的 `if` / `uses` / `continue-on-error` 与 base **逐字段相等** | 给产出 step 加 `if:false` / `continue-on-error` / 换 action 版本（A17/A37/**X8**） |
| **(e)** 产出 **输出接线** | 产出 job `outputs.<K>` **逐键归一后**与 base 相等（键缺失 / 改值 / 改指其它 step ⇒ 红）；产出 step `with` 的**非 `filters` 键**（键集合 + 值）与 base 相等 | 改写 / 删除 `outputs.<K>`、改指空产出 step、加 `list-files` / `predicate-quantifier`（**X1/X2/X3/X4**、`with` 变体） |
| **(d)** 消费点未搬走 | head 侧仍有 job 的 `if` 引用 `needs.<J>.outputs.<K>` | 把消费点搬到任意其它 job（A5/A5b/A5p/A5pb） |
| **(d1)** 消费 job 存在 | 同**消费 job key**（本仓 = `image-guard`）在 head **存在** | **整删门禁本体 job** / 改名（A24b/A41） |
| **(d2)** 消费 job **执行性字段** | 该消费 job 的 `if` / `needs` / `strategy` / `continue-on-error` / `uses` / `concurrency` 与 base **逐字段相等** | 消费 `if` 改恒假 / 改写法、删 `needs`、加 `coe`、空 `strategy`（A23a/b/c、A24c、A28、A30、A36、X7） |
| **(d3)** 消费 step **执行性字段** | 该消费 job 的**每个**（base 侧可定位的）step 在 head **存在且唯一**、其 `if` / `uses` / `continue-on-error` 与 base **逐字段相等**（口径 (i)：按 step `name`，无 `name` 用 `uses` 兜底） | 给消费 step 加恒假 `if` / 删消费 step / 换 `uses` 版本（A27/A39、`uses` 变体） |

**任何一步不可识别 ⇒ 显式 fail-closed 红**（不猜、不退回旧启发式）。

### 6.2.1 判据的封闭集合与**封闭性论证**（第 4 轮，D1）

**动机**：此前每轮审查都能找到「某个守门相关属性尚未纳入不削弱断言」的新构造（第 1 轮：取值口径；第 2 轮：产出侧 `if`；第 3 轮：消费侧节点；第 4 轮：产出 `outputs` 接线）⇒ **逐属性补断言本质不可穷尽**。第 4 轮把判据升级为**可枚举的封闭字段集合**：base 侧守门链内每个 job/step 的下列字段，head 侧**必须逐字段相等**（归一后）：

| 类别 | 字段 | 归一 | 依据 |
|---|---|---|---|
| **执行性** | job：`if` / `needs` / `strategy` / `continue-on-error` / `uses` / `concurrency`；step：`if` / `uses` / `continue-on-error` | `if` / `continue-on-error` 用表达式口径（下标归一 + 空白折叠）；`needs` 集合口径；`uses` 标量口径；`strategy` / `concurrency` 递归规范形（键序无关） | 任一被削弱都可能使该 job/step **恒不执行**（`if` / `needs` 跳过、`strategy` 空矩阵 ⇒ 零 job、`concurrency` 取消 ⇒ 沿 `needs` 链下游 skipped；**job 跳过 = 官方语义 Success，E5**）或**静默吞掉失败**（`continue-on-error`、`uses` 换成「成功但不产 output」的 action）⇒ **输出为空 ⇒ 消费侧 `if` 恒假 ⇒ 静默零覆盖** |
| **输出接线** | 产出 job `outputs.<K>` 值；产出 step `with` 的非 `filters` 键 | `outputs` 用表达式口径（下标归一 + 空白折叠）；`with` 非 `filters` 键用递归规范形 | 被改成恒假 / 被删除 / 改指其它 step ⇒ `needs.<J>.outputs.<K>` 解析为非 `'true'` ⇒ 消费侧 `if` 恒假；`list-files` / `predicate-quantifier` 改变 `image` 输出语义 |
| **触发面**（v1.6 / #116，**第三类**） | 本 workflow 自身 `on.pull_request_target` 下的**官方过滤键**：`types` / `branches` / `branches-ignore` / `paths` / `paths-ignore`（**恰 5 个、可枚举**） | `types` 用**集合下界**口径（`⊇ {opened, synchronize, reopened}`，键缺失按官方默认三型判绿）；`paths` / `paths-ignore` / `branches` / `branches-ignore` 用**存在即红**口径；集合外的键 ⇒ fail-closed 红 | 触发面决定「**本检查是否会在某个 PR 上被实例化**」——任一非空过滤都会**缩小**触发面 ⇒ 被过滤掉的 PR 上本检查**根本不产生 run**（连红都不产生，比假绿更隐蔽：§10.1 ① 的同型失效）。详见 **§8.4** |

**封闭性论证（为什么集合之外的字段不改变「是否执行」或「输出值」，因而不产生零覆盖）**：本检查的命题是「守门链**运行期是否仍在覆盖**」，其可静态判定的维度**只有两个**：

1. **节点是否执行**——由**执行性字段**决定。集合外的执行相关字段逐一排除：
   - `permissions` / `env` / `timeout-minutes` / `run` 正文：**至多令该 job 失败（红、可见）**——`permissions` 不足 ⇒ checkout / dorny 报错 ⇒ **红**；`timeout` 过短 ⇒ 超时 **红**；`run` 正文写坏 ⇒ **红**。**失败是可见的**（下游 skipped 为 Success，但失败 job 本身已报红 ⇒ **非静默**）。
   - step 顺序 / 新增 step：不改变既有 step 的「执行与否」与其 `if` / `uses`；新增 step 不在 base 断言范围内（只比对 base 侧身份）。
   - `concurrency` 被**纳入**而非排除：其可令 job 被取消并沿 `needs` 链下游 skipped，属「执行性」边缘；纳入以消除歧义（代价：合法新增 job 级 `concurrency` 判红，宁严勿松）。
   - **仅运行期语义**（输入使过滤恒不匹配、fork `ref` 不可解析、dorny 语义变更等）**无法静态判定** ⇒ 如实登记为边界（§9.1 ①②③），**不在本集合的静态闭合面内**（本检查不声称能判定）。
2. **输出是否可能取值**——由**输出接线**决定（产出 job `outputs` 值 / 产出 step `with` 非 `filters` 键 / `filters.image` 下界）。集合之外不存在其它「产出值的来源」：`outputs.<K>` 的取值只由 `steps.<ID>.outputs.*` 决定，而 `<ID>` 的「是否执行 / 输出语义」已由 (a)/(a')/(b)/(e) 覆盖。

⇒ **集合 {执行性} ∪ {输出接线} 覆盖了「节点是否执行」与「输出是否可能取值」两个充要维度**；其余字段的唯一影响是把**静默**转成**可见红**，故不纳入（其削弱在本检查中**不产生假绿**）。**配置面**（ruleset）与 **`ci.yml` 自身的触发面**（`on.pull_request.paths` 的 A25）属**另一轴**，显式登记为 B 类边界（§9.1 边界②），亦不在本集合内。

**第三类「触发面」的纳入（v1.6 / #116，采纳决策记录 DR-116-01 的 N6 建议）**：

前两类断言的**判据主体**都是 `ci.yml`（守门链），回答「**守门链运行期是否仍在覆盖**」。第三类**判据主体换成 `guard-selfcheck.yml` 自身**，回答另一个问题：「**本检查自己会不会在某个 PR 上被实例化**」——若本检查自身的 `on.pull_request_target` 被加上任何非空过滤键，被过滤掉的 PR 上本检查**根本不产生 run**（连红都不产生），**其失效形态与 §10.1 ① 的「workflow 被判 Invalid ⇒ 检查从未运行」同型**，比假绿更隐蔽。

**可枚举性（与既有两类同型 ⇒ 判据封闭）**：官方对 `pull_request_target` 的**触发过滤键恰为 5 个、可枚举** —— `types` / `branches` / `branches-ignore` / `paths` / `paths-ignore`。其中**任何非空过滤都只会缩小触发面**（不存在「扩大触发面」的过滤键）⇒ **「出现即红」对该 4 键成立**（`types` 另走集合下界，见 §8.4.4）⇒ 判据**封闭**，与 §6.2.1 前两类的封闭性论证同构。

**为何不采用「值相等」口径**：若改为「head 侧 `on.pull_request_target` 与 base 侧**值相等**」，则日后 base 侧**合法地**新增一个过滤键时，判据会**连带放宽** ⇒ 违反 issue #116「三种写法须全覆盖」的措辞（判据只许收紧、不许随 base 漂移）。

> **范围澄清（重要）**：第三类**只**覆盖 **`guard-selfcheck.yml` 自身**的 `on.pull_request_target`。它**不**覆盖其它 workflow 的触发面（§9.1 边界② 的 A25 = `ci.yml` 的 `on.pull_request`）。**证据**：`ci.yml` 当前 `on.pull_request` **已含** `paths-ignore: ["docs/**","**/*.md","LICENSE",".gitignore"]`（本仓合法基线）⇒ 若把四键 deny-list 套到 `ci.yml`，**当前 `main` 会立即恒红** ⇒ 该扩展在**未先设计例外白名单**前不可行 ⇒ **#116 不覆盖 A25**，A25 维持「另立票」登记（§13 R9）。

**边缘字段归类结论（主理人 D1 待评估项）**：

| 字段 | 结论 | 理由 |
|---|---|---|
| job `concurrency` | **纳入**（执行性） | `cancel-in-progress` 取消 job ⇒ 沿 `needs` 链下游 skipped = Success；与 `strategy` / `continue-on-error` 同为「job 级执行控制」 |
| job `permissions` / `env` / `timeout-minutes` | **登记**（内容面，不纳入） | 至多令 job **可见失败（红）**，不产生静默零覆盖 |
| step `with` 的非 `filters` 键（`list-files` / `predicate-quantifier` 等） | **纳入**（输出接线） | 改变 `image` **输出语义**（`list-files` 使 output 由 `'true'` 变为文件列表 ⇒ 消费侧 `== 'true'` 恒假）⇒ 静默零覆盖 |
| 产出 job `outputs` 的**非门禁键** | **一并绑定** | 同属产出 job 的「输出接线」；base 侧每个键在 head 归一后须相等（新增键不判红 ⇒ 不误伤扩项） |

> **(d3) 口径选择**：实现**口径 (i)**（按 step 身份定位并逐一比对**执行性字段**），**不选**更粗的口径 (ii)「head 消费 job 的 `if` 集合 ⊆ base 的 `if` 集合」。理由：(i) **可定位到具体 step**（失败信息含 step 身份）且同时闭合「消费 step 被加 `if`」与「消费 step 被删」两类；口径 (ii) 无法区分 step 增删、亦无法定位失败点。**可判定性**：`name` / `uses` 是 step 的静态字段、YAML 解析后即稳定结构 ⇒ 可判定；仅在「同身份重复」时转 fail-closed（不猜）。该判定**不含**「消费 job 的引用集合相等」，故不影响 A20/A21 类合法新增。

> **为何消费节点完整性 (d)/(d1)–(d3) 关键**：第 2 轮把锚改为 **base 侧**，但 (a)(b)(c) **只绑定产出链**、(d) **只做存在性判断**（`(J,K) in head_refs`）⇒ **消费节点（`image-guard` 本体）**——即守门本体——被删 / 其 `if` 被改恒假 / 其 step 被加 `if:false` 时，只要有**任意** job 仍引用该 token，检查即**静默绿**。第 3 轮**对称扩展到消费侧**（A23/A24/A27/A28/A30），第 4 轮并入**执行性字段集合**（含 `strategy` / `uses` / `concurrency`）⇒ 消费侧与产出侧同为本检查的闭合面。

> **为何 (d) 与 (d1)–(d3) 并存**：二者互补 —— (d) 断言「引用 token 未被整体搬离」，(d1)–(d3) 断言「**原消费节点本体**未削弱」。仅靠 (d) 会被「新增无害 job 承载引用 + 删/改消费本体」绕过（A24b/A24c/A30）；仅靠 (d1)–(d3) 则无法覆盖「消费节点整体消失**且**引用也消失」。二者并存 ⇒ 双向闭合。

**为何不是「首个同名 action」**：若按「首个 `uses` 以 `dorny/paths-filter` 开头的 step 命中即返回」，PR 只需在**真实步骤之前**插一个 decoy `dorny/paths-filter` step（其 `filters.image` 含基线条目）即可**遮蔽**真实步骤的删行 ⇒ 假绿。改按**消费链**定位、并以 **base** 为锚后，decoy（未被任何 job 输出引用）与「消费点转移」一概不参与判定 ⇒ 遮蔽面闭合（见 §9 B8、§8.2 A2/A3）。

**失败归因（`if` 表达式解析的健壮性）**：解析只提取 `needs.<J>.outputs.<K>` 与 `steps.<ID>.outputs.` 两个**引用 token**（正则），**不解析**表达式文法 ⇒ 复合条件（`&&` / `||`）、引号包裹、`needs` 多依赖均不影响命中。**第 2 轮起，正则先经括号下标归一**（把 `['x']` / `["x"]` 归一到 `.x`）⇒ 兼容 `needs['changes'].outputs['image']` / `steps['filter'].outputs['image']` 等**合法下标写法**（修 A8 假红）。**第 3 轮起，`if` 的「与 base 相等」比较**（产出侧 (c)/(a') 与消费侧 (d2)/(d3)）**先做括号下标归一、再折叠连续空白并 strip**（`_norm_expr`；第 4 轮起 `outputs.<K>` 亦复用该口径）⇒ **下标写法差异与空白重排视为等价**（修 A8/A33 假红），而任何**语义变化**（新增 / 收紧 / 恒假写法）**仍红**（夹具 A8/A33/A33c 绿 vs A33b/A16/A17/A23 红，§8.2）。提取不到 / job 或 step 解析不出 / id 缺失或重复 / action 非 dorny / `filters` 非串 / `image` 非列表 / base 或 head 结构不可识别等**任一情形**，一律显式 fail-closed（`could not locate ...` / `structure unrecognizable ...`），**不**退回「首个同名 action」，也**不**抛裸异常。

**为什么是「⊇ 下界」而非「== 相等」**：白名单**新增**条目（如未来再加第三个 workflow 自守）应**允许**（不假红）；只有**删掉**基线条目才红 ⇒ 下界断言精确表达不变量「自守条目必须仍在」。基线集合现仅含 1 条（本票残余边界的**核心条目**）；扩展基线 = 往 `BASELINE_IMAGE_ENTRIES` 加条目，机制不变。

**覆盖两条残余边界**：① 删条目 ⇒ 集合缺基线条目 ⇒ 红；② `ci.yml` 外层 YAML 坏 ⇒ `safe_load` 抛错 ⇒ 红（REST 取原始字节，不受「GitHub 能否解析」影响）。

### 6.3 判定证据源（不读 PR 检出的落实）

- **不 `actions/checkout`**、**不执行任何 PR 代码**；
- 每条断言均经 `gh api -H "Accept: application/vnd.github.raw" repos/{owner}/{repo}/contents/{path}?ref={ref}` 读**原始字节**；`ref` = **`base_sha`**（可信锚）或 **`head_sha`**（被测）；
- `head_sha` 取自 `github.event.pull_request.head.sha`、`base_sha` 取自 `github.event.pull_request.base.sha`（payload），**不**取 `GITHUB_SHA`（该值在 `pull_request_target` 下 = **默认分支末次提交**，E4）。

## 7. 验收标准（AC）

> 对应 issue #103 的 AC①–④ 定稿；全部可判定。

- [x] **AC1（删行必红）**：存在一条检查，在「删掉 `ci.yml` 中 `filters.image` 的自守条目 `.github/workflows/ci.yml`」的 PR 上**必然失败**。——**判定逻辑：部分闭合（限定于「已枚举的构造」）**：打桩复算下，「朴素删行 / 诱饵遮蔽 / 消费点转移 / 产出侧或消费侧加恒假 `if` / **产出 job `outputs.<K>` 接线被改·删·改指（X1–X4）** / **产出 step `continue-on-error`·`with` 被改（X8）** / 消费 job 被删 / 消费 step 加 `if` / 消费 `needs` 被删 / 结构不可识别」均报红（§8.2）。**措辞限定（第 4 轮修正）**：本 AC 声称「**已枚举的构造已闭合**」，**不**声称「**静态可判定构造已闭合**」——判定面由 §6.2.1 的**封闭集合**定义（**执行性 + 输出接线**）；**未覆盖**：① **仅运行期才可判定**的失效（§9.1 ①）、② **触发面**削弱（A25，§9.1 ②）、③ **架构 / 配置面**（§9.1 ③）。**线上实测已完成**：删自守条目行的 trail head `546e9e2` 上 `Guard Self-Check = failure`（run id `36013274614`，判据 `whitelist entries weakened: ['.github/workflows/ci.yml'] missing from filters.image ...`）；正向绿基线 `f25faab` = `success`（run id `36013102684`）。详见 §8.3。
- [x] **AC2（不读 PR 检出自举测试）**：该检查的判定**不依赖被测 PR 自身检出的文件内容**；须有自举测试 —— 构造一个删掉该行（或改坏 workflow 语法）的 PR，观察该检查**仍报错**。——**已闭合（线上实测）**：三段式成立 —— ① 正向绿基线 `f25faab` = `success`（run id `36013102684`，证明「环境可跑通 + 判定可放行」）；② 删自守条目行 `546e9e2` = `failure`（run id `36013274614`）；③ 改坏 `ci.yml` YAML 语法 `2378b33` = `failure`（run id `36013518279`，fail-closed）。判据原文见 §8.3。判定全程经 REST 读 head 侧原文、**不 checkout PR**（§6.3），故与「被测 PR 自身检出」解耦。
- [x] **AC3（不改既有守门）**：**不改变**现有镜像守门的判据与其运行契约断言（`changes` 判据 + `Assert runtime contract (non-root + /data writable)` step 一字不动）。——**已闭合**：`git diff main...HEAD -- .github/workflows/ci.yml` 为空。
- [x] **AC4（自守边界）**：明确写清本检查**自身的自守边界**（谁来保护它不被同样的手法绕过）——见 §9。——**已闭合**（§9 B1–B12 + §9.1 威胁模型边界）。
- [x] **AC5（零残留）**：自举用的 trail PR **关而未合**、trail 分支**已删**；无遗留新增分支 / PR。——**已闭合（线上实测）**：trail PR **#112** 已 **CLOSED**（未合并，`mergedAt=null`），其分支已删除并经 `gh api` **复算返回 404** ⇒ **零残留成立**。

> **另见**：上表 AC1–AC5 对应 issue **#103** 的 AC①–④ 与零残留要求；issue **#116（触发面加固）** 的验收条目为 **AC116-1 – AC116-12**，见 **§8.4.2**（其残留见 §8.4.6 / §13 R11–R13）。

## 8. 落地与自举实测

### 8.1 时序依赖（**关键**：trail 实测不能在 PR-1 自身上完成）

`pull_request_target` 的 workflow 定义**读自 base**（E4）⇒ **PR-1 创建时该 workflow 尚不在 base** ⇒ PR-1 自身**不会**触发本检查 ⇒ **trail 自举实测必须在 PR-1 合并入 `main` 之后**、另开 `test/103-*` 分支进行。这是本票的**结构性时序依赖**。

**已完成（回填）**：workflow 已合并入 base（**引入**守门自检检查本体 = PR #111 / 合并 commit `a32d3de`；随后**修复**其 `run` 正文非法表达式 = PR #113 / 合并 commit `a141e8d`）后，已按 §8.3 三段式执行**线上 trail**，三段结论**均符合预期**（① 绿 / ② 红 / ③ 红，见 §8.3 实测表）⇒ AC2 线上判据**已闭合**。

**同一时序约束亦适用于 §10.1 ⑤ 的静态护栏 step**：该 step 是在 **PR #114** 中**新增进 `guard-selfcheck.yml`** 的 ⇒ 同上理，**#114 自身的 `Guard Self-Check` 通过不能证明新 step 在 GitHub 上跑过**（`pull_request_target` 在 #114 上运行的仍是 base 侧旧定义）⇒ 其**首张线上实证**须在 **#114 合并（`b801404`）后**另开一次性 trail（**PR #115**）取得；实测见 **§10.2**。

### 8.2 本地自举单测（已执行，方法 + 结果）

在合并前，先以**等价打桩**验证判定逻辑（不打网络）：将 workflow 的判定脚本抽出，用桩替换 `subprocess.run`（`gh api` 调用），**同时喂入 base 与 head 两侧 fixtures**，逐场景核对退出码。第 1 轮 11 场景、第 2 轮扩至 29、第 3 轮扩至 49；**第 4 轮由修复者另建独立夹具台复算 40 场景**（含第 1–3 轮关键回归，及本轮新增的产出 `outputs` 接线 X1–X4、产出 step `coe` / `with` X8、执行性字段族变体；**0 mismatch**）。

**第 1 轮审查**发现「诱饵 step 遮蔽」假绿后，锚定面从「首个同名 action」改为**消费链**。**第 2 轮审查**发现「消费链锚定」仍存在**同根因**的假绿：静态结构无法区分「被定位到的产出链**运行期是否被执行**」（消费点转移到 `if:false` 的产出 job ⇒ A5p；给守门链加 `if:false` ⇒ A16/A17）。**第 3 轮审查**进一步发现**消费侧**（`image-guard` 本体）未被断言 ⇒ 门禁 job 被删 / 其 `if` 被改恒假 / 其 step 被加 `if` 仍静默绿（A23a/b/c、A24b、A24c、A27、A28、A30）。**第 4 轮审查**发现**产出侧** `outputs.<K>` 接线未被断言（改写 / 删除 / 改指产出 job `outputs.image` ⇒ 消费侧 `if` 恒假 ⇒ 静默绿；**X1–X4**），与产出 step `continue-on-error`（**X8**）同根因。第 4 轮据此把断言**对称扩展到消费节点**并升级为**封闭字段集合**（§6.2.1），补 `outputs` / `with` 接线断言，并修复 A33 空白重排假红、D4 潜伏恒红。下表为**第 1–4 轮合并场景**（带「第 4 轮」标注者为本轮新增 / 复核；base 侧默认 = 「正本 `ci.yml`」；凡标注 base 系构造者属该场景显式设定；head 侧为构造）：

> **本表范围**：只收 **#103 轮已实测**的打桩构造（每行均带 ✅ 实测结论与判据原文）。**未实测**的构造（如 #116 的 `types: [labeled]`，见 §8.4.2 **AC116-5** / §8.4.5 阶段④）**不列入本表**，待其 build PR 取证后另立实证章节，**不在本表补行**。

| # | head 侧构造 | 期望 | 实测 |
|---|---|---|---|
| A1 | 纯删真实门禁链的基线行 | 红 | ✅ exit 1，`whitelist entries weakened: ['.github/workflows/ci.yml'] ...` |
| A2 | 删真实行 + 真实 step 之前插 decoy（含基线） | 红 | ✅ exit 1，同上（decoy 不参与判定） |
| A3 | decoy 插在无消费者的另一 job | 红 | ✅ exit 1，同上 |
| A4 | `image-guard` 的 `if` 不再引用任何 `needs.*.outputs.*`（基线条目保留） | 红 | ✅ exit 1，`gating consumption point relocated ...`（仅 (d) 触发） |
| **A5** | 消费点转到**活跃新 job**（含基线）+ 删原链基线行 | **红**（口径变化，D1-A） | ✅ exit 1，(d)+(b) 双红 |
| **A5b** | 消费点转到活跃新 job，**原基线条目保留** | 红 | ✅ exit 1，`gating consumption point relocated ...`（仅 (d)） |
| **A5p** | 消费点转到 `if:false` 的产出 job + 删原链基线行 | **红** | ✅ exit 1，(d)+(b) 双红 |
| **A5pb** | 消费点转到 `if:false` 产出 job，原基线条目保留 | 红 | ✅ exit 1，`gating consumption point relocated ...`（仅 (d)） |
| A5q | 多个 `needs` 消费点（base 两条链），其一被削弱 | 红 | ✅ exit 1，`whitelist entries weakened ... job 'changes_b'` |
| A6 / A6b | 同 job 内 id 重复（双 dorny / 混合） | 红 | ✅ exit 1，`producing step id 'filter' ... not unique (count=2)` |
| A7 | 产出 step `uses` 非 dorny | 红 | ✅ exit 1，`no longer uses dorny/paths-filter` |
| **A8** | `if`/`outputs` 用下标语法（`needs['changes'].outputs['image']`），白名单与 base 一致 | **绿**（修后不假红） | ✅ exit 0，`PASSED` |
| A9 | `if` 为复合表达式（`&&`），守门链与 base 等价 | 绿 | ✅ exit 0 |
| A10 / A10b | `filters` 非 str / `image` 非 list | 红 | ✅ exit 1，`filters.image unrecognizable at head` |
| A11 | head 侧 `ci.yml` YAML 语法坏 | 红 | ✅ exit 1，`YAML invalid at head ...` |
| A12 / A12b | 顶层为 list / 标量 | 红（显式 `::error`，无裸 traceback） | ✅ exit 1，`structure unrecognizable at head (top-level is not a mapping)` |
| A13 | head 侧 `ci.yml` 404 | 红 | ✅ exit 1，`ci.yml not found at head` |
| A14 | head 侧 `guard-selfcheck.yml` 404 | 红 | ✅ exit 1，`guard workflow ... not found at head` |
| A15 | 产出 job 缺失 | 红 | ✅ exit 1，`producer job 'changes' missing at head` |
| **A16** | gating job 加 `if: false`（白名单不动） | **红** | ✅ exit 1，``producer job 'changes' `if` changed vs base (base=None head=False)`` |
| **A17** | gating step 加 `if: false` | **红** | ✅ exit 1，``producing step 'filter' in job 'changes' `if` changed vs base (base=None head=False)`` |
| A18 | base 与 head 的守门链完全等价 | 绿 | ✅ exit 0 |
| A19 | base 与 head 的 job/step `if` 相同（两侧同一表达式） | 绿 | ✅ exit 0 |
| A20 | head 新增无关 job / 无关 dorny 用法（守门链未动） | 绿（不误伤） | ✅ exit 0 |
| A21 | head 在门禁链 `filters.image` **增补**新条目（保留基线条目） | 绿（允许扩项） | ✅ exit 0 |
| A22 | base 侧本身解析不出守门链（base 结构异常） | 红（fail-closed） | ✅ exit 1，`could not locate the base gating chain ...` |
| **A23a** | 消费 job `image-guard.if = needs.changes.outputs.image == 'false'`（引用保留） | **红** | ✅ exit 1，``gating consumer job 'image-guard' `if` changed vs base`` |
| **A23b** | 消费 `if = … == 'true' && false` | **红** | ✅ exit 1，同上 |
| **A23c** | 消费 `if = … != 'true'` | **红** | ✅ exit 1，同上 |
| **A24b** | **整删 `image-guard` job** + 新增无害 job 承载该引用 | **红** | ✅ exit 1，`gating consumer job 'image-guard' missing at head ...` |
| **A24c** | `image-guard.if = false` + 新增无害 job 承载引用 | **红** | ✅ exit 1，``gating consumer job 'image-guard' `if` changed`` |
| **A27** | 给消费 job 的 `Build image via compose` step 加 `if: false` | **红** | ✅ exit 1，``gating consumer step [name:Build image via compose] `if` changed`` |
| **A28** | 删消费 job 的 `needs`（`if` 引用保留） | **红** | ✅ exit 1，``gating consumer job 'image-guard' `needs` changed vs base`` |
| **A30** | `image-guard.if = false` + 新增**活跃**新 job 承载引用 | **红** | ✅ exit 1，``gating consumer job 'image-guard' `if` changed`` |
| **A33** | 产出 `if` 仅**空白重排**（「`$` + 双花括号」包裹 `  x  !=  'y'  `（多余空白）vs base 紧凑写法） | **绿**（修后不假红） | ✅ exit 0，`PASSED` |
| **A33b** | 产出 `if` **语义变化**（`!=` → `==`） | 红 | ✅ exit 1，``producer job 'changes' `if` changed`` |
| **A33c** | 消费 `if` 仅**空白重排** | **绿** | ✅ exit 0，`PASSED` |
| **A25** | `on.pull_request` 加 `paths: ["Dockerfile","pom.xml"]`（不含 `ci.yml`） | **绿**（B 类边界，已裁决；**仅登记、不闭合**） | ✅ exit 0，`PASSED`（见 §9.1 边界②）。**注**：本行 **A25 = `ci.yml` 触发面**；与 issue #116 正文所称「A25」（指**本检查自身** `types` 被改成 `[labeled]`）**编号冲突、指代不同** —— 后者 = **#116 的对象**，其「PR 阶段报红」由 **§8.4 / AC116-5** 定义，**须待 #116 的 build PR 落地并完成四段式取证后**方才成立（**当前未实现、未取证**）；前者**不**被 #116 覆盖（编号冲突见 §13 R9 表后脚注） |
| A36 | 消费 job 加 `continue-on-error: true` | 红 | ✅ exit 1，``gating consumer job 'image-guard' `continue-on-error` changed`` |
| A37 | 产出 step `uses` 换版本（`dorny/paths-filter@v9`） | 红 | ✅ exit 1，``producing step 'filter' in job 'changes' `uses` changed vs base`` |
| A38 | 产出 job 加 `continue-on-error: true` | 红 | ✅ exit 1，``producer job 'changes' `continue-on-error` changed`` |
| A39 | 删消费 job 的关键 step（`Build image via compose`） | 红 | ✅ exit 1，`gating consumer step [...] missing or ambiguous (count=0)` |
| A40 | 消费 job **新增** step（base 步骤全在） | 绿（允许扩项） | ✅ exit 0，`PASSED` |
| A41 | 消费 job **改名**（`image-guard` → `guard2`，`if` 不变） | 红 | ✅ exit 1，`gating consumer job 'image-guard' missing at head ...` |
| **A9b** | base 为正本（消费 `if` 为**朴素**写法）、head **单侧**改复合写法 | 红（**d2 语义变化**，非假红） | ✅ exit 1，``gating consumer job 'image-guard' `if` changed`` |
| **D4-green** | base 混入「引用**非 dorny 产出**」的无关 job（`foo` 引用 `bar.outputs.x`，`bar` 产出非 dorny）；head 同 base | **绿**（不恒红） | ✅ exit 0，`PASSED` |
| **D4-asserts** | 同上 base；head 删真链基线条目 | 红（合法链照常断言） | ✅ exit 1，`whitelist entries weakened ...` |
| **X1** 第 4 轮 | 产出 `changes.outputs.image := "false"` | **红** | ✅ exit 1，``producer job 'changes' `outputs.image` changed vs base (base='「`$` + 双花括号」包裹 steps.filter.outputs.image' head='false')`` |
| **X2** 第 4 轮 | 删 `changes.outputs` 整个映射 | **红** | ✅ exit 1，同上（`head=None`） |
| **X3** 第 4 轮 | `outputs.image :=` （「`$` + 双花括号」包裹 `steps.other.outputs.image`）（改指空产出 step） | **红** | ✅ exit 1，同上 |
| **X4** 第 4 轮 | `outputs.image := ""` | **红** | ✅ exit 1，同上 |
| **X8** 第 4 轮 | 产出 step（`filter`）加 `continue-on-error: true` | **红** | ✅ exit 1，``producing step 'filter' in job 'changes' `continue-on-error` changed`` |
| **E1** 第 4 轮 | 产出 job 加 `needs` / `strategy` / `concurrency`（三夹具） | 红 | ✅ exit 1，``producer job 'changes' `needs` / `strategy` / `concurrency` changed`` |
| **E2** 第 4 轮 | 消费 job 加 `strategy.matrix.include: []`（**空矩阵**；即第 4 轮审查的 X7） | **红**（`strategy` 属执行性集合 ⇒ **顺带闭合**，见 D3） | ✅ exit 1，``gating consumer job 'image-guard' `strategy` changed`` |
| **E3** 第 4 轮 | 消费 step 换 `uses` 版本（`actions/checkout@v4` → `@v5`） | 红 | ✅ exit 1，``gating consumer step [name:Checkout] `uses` changed`` |
| **E4** 第 4 轮 | 产出 step `with` 加 `list-files: csv` / `predicate-quantifier: every` | 红 | ✅ exit 1，``producing step 'filter' in job 'changes' `with` (non-filters) changed`` |
| **A1'/A2'** 第 4 轮 | 复核：纯删基线行 / 前置 decoy + 删真实行 | 红 | ✅ exit 1，`whitelist entries weakened` |
| **A23a'/A23b/A23c** 第 4 轮 | 复核：消费 `if` 分别改 `=='false'` / `… && false` / `… != 'true'` | 红 | ✅ exit 1，``gating consumer job 'image-guard' `if` changed`` |
| **A8b** 第 4 轮 | 消费 `if` 用下标语法（`needs['changes'].outputs['image']`） | 绿 | ✅ exit 0，`PASSED` |

此外核对**真实 `ci.yml`**：base==head（未改动）⇒ exit 0；仅在 head 删除基线行 ⇒ exit 1（`whitelist entries weakened`）。

⇒ **结论**（复算范围 = 上表第 1–4 轮合并场景；第 4 轮另含独立 40 场景矩阵）：
1. **正本 + 未改动 ⇒ 绿**（A18/A31/A34）。
2. **静态可判定的守门链削弱 —— 收敛于 §6.2.1 的封闭集合（执行性 + 输出接线）**：删 / 弱化条目、搬消费点、**产出或消费 job·step 的任一执行性字段被削弱**（`if` / `needs` / `strategy` / `continue-on-error` / `uses` / job `concurrency`）、**产出 job `outputs.<K>` 接线被改·删·改指**、**产出 step `with` 非 `filters` 键被改**、结构不可识别 / 文件缺失 / 语法坏 ⇒ **一律红**（A1–A17、A22–A30、A36–A41、**X1–X4、X8、E1–E4**）。**注意**：本条的「一律红」**限定于上述封闭集合**，**不**等同「凡静态可判定构造一律红」（第 4 轮据 X1–X4 证伪旧的全称表述）。
3. **合法改动不误伤**：A20/A21（新增无关 job / 扩项）、**A8/A8b/A33/A33c**（下标写法 / 空白重排）、A40（消费 job 增 step）、A9（两侧同一复合表达式）⇒ 绿。
4. **第 4 轮复算范围内未发现新的假绿路径**（产出 `outputs.<K>` 接线 X1–X4、产出 step `coe` / `with` X8、消费执行性字段族均已闭合）。**但本检查历史上已被四轮独立审查各证伪一次**（第 1 轮诱饵遮蔽、第 2 轮产出侧运行期禁用、第 3 轮消费侧、第 4 轮产出 `outputs` 接线）⇒ **不得据此声称「无假绿路径 / 已穷尽」**；边界一律如实登记（见 §9.1）。
5. **仍不可静态闭合**（如实登记 + 另立票）：① **仅运行期才可判定**的失效（如某输入语义使过滤恒不匹配）；② **触发面**削弱（`on.pull_request.paths` 排除 `ci.yml`，即 **A25**）；③ **架构 / 配置面**（ruleset `workflows` rule / `image-guard` 迁 `pull_request_target`）—— 见 §9.1 边界①②③。
6. **仍存在的假红面**（如实登记）：合法但**等价的门禁结构重构**会红（改名 job/step / 换 action 版本 / 等价改写 `if` / 消费 job 删步 / **新增 job·step 级 `strategy`·`concurrency`·产出 `with` 键**，R1「宁严勿松」，当前非 required **不阻断**）；**纯写法差异**（下标写法 / 空白重排）类假红已由归一（`_norm_expr` / `_canon`）**消除**。线上自举（真实 GHA run）见 §8.1。

（复算方式：抽取 workflow 内嵌 python 判定脚本 → 桩替换 `subprocess.run` 喂入 **base + head 两侧** fixtures → 逐场景核对退出码。本地环境注：本机 Git Bash 对 shell heredoc 报 `cannot create temp file for here-document`，属**本地沙箱限制**，非脚本缺陷；GitHub `ubuntu-latest` 支持 heredoc。故本地以「抽取脚本 + 打桩 `subprocess.run`」等价执行。）

### 8.3 线上自举实测（**已完成**，三段式判据 + 实测证据）

为避免「脚本在 runner 上根本跑不起来 ⇒ 恒红」与「断言真生效 ⇒ 删行红」不可区分，线上 trail **必须**含**正向绿分支**，构成**三段式**（缺 ① 则 ②③ **不可证伪**）：

1. **① 正向绿基线**（**必要条件**）：在一个**未改动**白名单的正常 PR 上，`Guard Self-Check` 必须为**绿** ⇒ 证明「环境可跑通 + 判定可放行」；**若 ① 从未跑过，「②③ 红」无法区分是「断言真生效」还是「脚本在 runner 上根本跑不起来 ⇒ 恒红」（第 1 轮审查的必答结论）。**
2. **② 删行必红**：在 trail PR 上删掉白名单自守条目一行 ⇒ `Guard Self-Check` 必须**报红**。
3. **③ 语法坏必红**：改坏 `ci.yml` YAML 语法（或造结构不可识别）⇒ `Guard Self-Check` 必须**报红**。

**实测结果（三段式，已完成）**：

| 阶段 | head commit | `Guard Self-Check` 结论 | run id | 判据原文 |
|---|---|---|---|---|
| ① 正向绿基线（仅新增文档探针，**未触碰** `ci.yml`） | `f25faab` | **success**（`event=pull_request_target`、`name=Guard Self-Check`） | `36013102684` | ——（放行，无失败条目） |
| ② 删掉 `ci.yml` 白名单里的 `- '.github/workflows/ci.yml'` 行 | `546e9e2` | **failure** | `36013274614` | `whitelist entries weakened: ['.github/workflows/ci.yml'] missing from filters.image of job 'changes'/step 'filter' (present=['**/pom.xml', '.mvn/**', 'Dockerfile', 'docker-compose.yml', 'pom.xml'])` |
| ③ 破坏 `ci.yml` YAML 语法 | `2378b33` | **failure**（fail-closed） | `36013518279` | `.github/workflows/ci.yml YAML invalid at head 2378b33...: while parsing a flow sequence ... push: [unclosed ... expected ',' or ']', but got ':'` |

⇒ **三段式成立**：① 证明「环境可跑通 + 判定可放行」（排除「恒红不可证伪」）；② 证明**删行必红**（AC1 的**线上实证**）；③ 证明**语法坏 fail-closed 必红**（AC2 的语法失效分支线上实证）。

**零残留（AC5）**：trail PR **#112** 已 **CLOSED（未合并，`mergedAt=null`）**，其分支已删除并经 `gh api` **复算 404** ⇒ 无遗留新增分支 / PR。

执行要点（回顾）：

- ①②③ 均基于合并后 `main` 进行（① 在正常 PR 上观测；②③ 另开 trail PR `test/103-<slug>`）。
- 每次观测记录 **run id**（见上表）+ `gh pr checks` / `gh run view` 实证。
- **无论成败：关 PR（不合并）+ 删分支，并复算 404**（零残留，AC5）。

> **交叉引用**：本节三段式针对的是**既有「不削弱」断言**（消费链）。§10.1 ⑤ 新增的**静态护栏 step**（`run` 正文表达式机检）的**首张线上实证**是**另一次**合并后一次性 trail，见 **§10.2**（trail PR #115）——读者勿把本节三段式误当作新 step 的自举证据。

### 8.4 #116 触发面加固：可执行交付规范（v1.6）

> **时态声明（v1.6，读本节与相关条目必读）**：本节是 **规范态**（v1.6 规范轮产出），**不是既成事实**。本规范中凡「已由 #116 闭合 / 已机制化判红 / 存在即红」等**完成态**表述（含 **§3.1 (2a)**、**§6.2.1 第三类「触发面」行**、本节全节、**§9 B13**、**§13 R9 (2a)**），一律指「**#116 的 build PR 落地并完成 §8.4.5 四段式取证之后**」的状态。**当前（v1.6）未实现、未取证**：`.github/workflows/guard-selfcheck.yml` 现仍只有 4 个 step（`Set up Python` / `Install PyYAML` / `Assert no GitHub expression inside workflow run bodies` / `Assert guard self-coverage intact`），**无任何触发面断言 step**（`trigger surface` / 「触发面」匹配数 = 0），其 `on.pull_request_target` 现为无过滤的 `types: [opened, synchronize, reopened]`。⇒ 读者**不得**据本规范推断「当前 PR 已受触发面断言保护」。

> **来源**：issue [#116](https://github.com/luochenfx/ecom-flowcart/issues/116)（OPEN）+ 已收敛决策记录 **DR-116-01**。本节是 #116 的**可执行交付规范**（实现与取证由 #116 的 build PR 承担）；本节**不声称**「已闭合触发面」或「已可阻止其生效」——只交付「**PR 阶段必红**」，残留见 §8.4.6 与 §13 R11–R13。

#### 8.4.1 选定方案与组合边界

**方案 C1**：在 `guard-selfcheck.yml` 内**新增一条 step**，对 **head 侧 `guard-selfcheck.yml` 自身的 `on` 块**做「触发面未被削弱」断言（`pull_request_target` 读 base 定义 ⇒ 该断言在**引入它的 PR 内不执行**，故取证必须走合并后 trail，见 §8.4.5）。

**组合边界（写死，不得突破）**：

| # | 边界 |
|---|---|
| ① | **不碰** ruleset `23927156`、**不动** required 构成（归 #104 已 CLOSED / 其后续票） |
| ② | **不新增第二个 workflow**（候选 C5「独立 workflow」/ C7「ruleset `workflows` rule」均被拒） |
| ③ | **不改** `ci.yml` / `ci-gate.yml` / `qodana_code_quality.yml` 一字 |

**验收口径**：#116 交付「**报红**」；「**阻止生效**」**不作为本票交付物**，显式登记为残留（§8.4.6 R-A），归 **#119（架构 / 配置面）或另立 follow-up 票**。依据：issue #116 验收要点逐字为「报红，**或**有其它机制确实阻止其生效」⇒ 二者取一即可收口。

#### 8.4.2 #116 验收条目（AC116，逐条可判）

> 判读通则：**「无 run」≠「红」**。每条「必红」条目必须取到**一次真实 run**（`event=pull_request_target`、`name=Guard Self-Check`）且 `conclusion=failure` + **判据原文**；若注入导致该 commit 上**根本不产生 run**，该条判为**取证失败**（须重新设计注入点），**不得**记为通过。

| # | 条目 | 判据 |
|---|---|---|
| **AC116-1** | 注入 `paths` 必红 | head 侧 `on.pull_request_target` 出现 `paths` ⇒ 新 step 报红，判据原文含键名 `paths` 与文件名 |
| **AC116-2** | 注入 `paths-ignore` 必红 | 同上，键名 `paths-ignore` |
| **AC116-3** | 注入 `branches` 必红（**可否决点 1；条件性**） | 同上，键名 `branches`。若可否决点 1 被否决 ⇒ 本条删除，并按 §8.4.7 登记新边界 |
| **AC116-4** | 注入 `branches-ignore` 必红（**可否决点 1；条件性**） | 同上，键名 `branches-ignore` |
| **AC116-5** | `types: [labeled]` 必红 | head 侧 `types` 不含默认三型 ⇒ 红（下界缺口须在判据中逐项列出） |
| **AC116-6** | **删掉显式 `types` ⇒ 绿**（反假红） | 键缺失按官方默认三型判绿；若判红即**假红缺陷**，须修 |
| **AC116-7** | **正向绿基线成立**（不可省） | 未改动的 trail commit ⇒ 该 step **绿**，且**四个 step 全部执行**（证明脚本在 runner 上跑得通 ⇒ 排除「恒红不可证伪」） |
| **AC116-8** | 形态 fail-closed 必红 | `on` 改为字符串 / 列表、删掉 `pull_request_target`、其值非映射、head 侧自身文件读不到 ⇒ **一律红** |
| **AC116-9** | 既有两断言不回归 | 四段式全程：既有 `Assert guard self-coverage intact` 与 `Assert no GitHub expression inside workflow run bodies` 在**其被执行时**结论与未注入时一致（`ci.yml` 未改动 ⇒ 绿） |
| **AC116-10** | 零残留 | trail PR **关而不合**（`state=CLOSED`、`mergedAt=null`）+ 分支已删 + `gh api` 复算远端 ref **404** |
| **AC116-11** | 不越界 | 本票 diff **仅** `.github/workflows/guard-selfcheck.yml`（新增 1 个 step）+ 规范文档；`ci.yml` / `ci-gate.yml` / `qodana_code_quality.yml` / ruleset **零改动**（`git diff main...HEAD -- <上述路径>` 为空） |
| **AC116-12** | P0 插值合规（自包含） | 新 step 的 `run:` 正文**不含**「`$` + 双花括号」序列（含注释内）；一切量经 `env:` 传入。该事实由**既有护栏 step** 在 AC116-7 的绿基线上**顺带机检**（即本仓自包含自检，不依赖本地辅助脚本） |

#### 8.4.3 硬约束（HC-1 – HC-10，实现方逐条对照）

| # | 硬约束 | 违反后果 |
|---|---|---|
| **HC-1** | **PyYAML 取键口径（最高优先级）**：`yaml.safe_load` 下 YAML 1.1 把 `on` 解析成**布尔键 `True`** ⇒ 取触发块**必须**走 `doc.get(True)`，并以字符串 `"on"` 作**回退**；两者同时存在且**不相等** ⇒ fail-closed 红；`True` 键的值**非 mapping** ⇒ 红 | 忽略 ⇒ 取到 `None` ⇒ 断言**永久静默绿**（比不做更糟）。**一手复算**：本仓 `pyyaml==6.0.3` 下 `safe_load(guard-selfcheck.yml)` ⇒ `top keys = ['name', True, 'permissions', 'concurrency', 'jobs']`、`get("on") -> None`、`get(True) -> {'pull_request_target': {'types': ['opened','synchronize','reopened']}}` |
| **HC-2** | **P0 插值硬约束**：新 step 的 `run:` 正文内**不得出现**「`$` + 双花括号」序列（**写在注释里也触发**）⇒ 整份 workflow 被判 **Invalid、永不运行**；一切量经 `env:` 传入 | §10.1 ① 的 P0 事故重演：本检查**对所有 PR 零覆盖**且**不产生任何 run** |
| **HC-3** | **deny-list 键集（存在即红）**：`paths` / `paths-ignore` / `branches` / `branches-ignore`；**并追加**：`pull_request_target` 下出现这 4 键与 `types` 之外的**未知键 ⇒ fail-closed 红** | 与 §10.1 ⑤「有意的全禁策略」同口径；**不得**日后以「误红」为由放宽 |
| **HC-4** | **`types` 下界集合** = `{opened, synchronize, reopened}`；判定 = `⊇`；**键缺失 ⇒ 按官方默认三型判绿**；非列表 / 空列表 ⇒ 红 | 用 `==` 会打成**假红**（删掉显式 `types` 是语义等价写法），与 §8.2 修 A8/A33 假红的方向**相反** |
| **HC-5** | **形态 fail-closed**：顶层非 mapping / 无 `on` / `on` 非 mapping / 无 `pull_request_target` / 其值非 mapping / head 侧自身文件读不到（沿用 `api_raw(SELF_PATH, HEAD)`）⇒ **一律红**，不猜、不静默通过 | —— |
| **HC-6** | **不改变本 workflow 自身的触发面**（保持无 `paths` / `if` / `needs`，恒定上报） | 保住 §11 第 3 条的 required 资格前提 |
| **HC-7** | **取证节奏不可省略项**：四段式（① 绿 ② `paths` 红 ③ `paths-ignore` 红 ④ `types: [labeled]` 红）；每段记录 **head sha + run id + 判据原文**；trail **关而不合 + 删分支 + 复算 404** | 缺 ① ⇒ ②③④ 不可证伪；②③ 合并 ⇒ 违反 issue「三种写法须全覆盖」 |
| **HC-8** | **step 位置**：紧跟 `Install PyYAML` 之后、**在既有 `Assert guard self-coverage intact` 之前** | 与 #115 先例同位（§10.2），保证**单变量归因**：新 step 先失败即 `exit 1`，job 停在该 step 上 |
| **HC-9** | **spec 写面**（本轮）：0010 §6.2.1 增「第三类：触发面」+ 封闭性论证、§9 增 B13、§13 R9 改写 + 增 R11–R13、§16 更新票号；**不改** `ci.yml` / `ci-gate.yml` / ruleset | —— |
| **HC-10** | **措辞红线**：spec 与 PR 描述中**不得**出现「已闭合触发面」「已可阻止其生效」「已闭合 B2」；只允许写「**PR 阶段必红已闭合**，『阻止生效』+ 合并后可删断言属登记残留」 | 越线即**过度声称**，须回改 |

#### 8.4.4 判定口径（实现方照此实现）

```
目标文件 = head 侧 SELF_PATH（".github/workflows/guard-selfcheck.yml"），经 api_raw(SELF_PATH, HEAD) 取原文（不 checkout）

(1) 取触发块（HC-1）
    doc = yaml.safe_load(text)                    # YAML 1.1 把 on: 解析为布尔 True
    t_bool = doc.get(True)      if isinstance(doc, dict) else None
    t_str  = doc.get("on")      if isinstance(doc, dict) else None
    两键都存在且不相等                -> 红（fail-closed）
    两者皆缺失 / 顶层非 mapping        -> 红
    trig = 存在者（优先 True 键）      # 非 mapping -> 红

(2) 取 pull_request_target 块
    prt = trig.get("pull_request_target")
    prt 缺失 / 非 mapping               -> 红
    注：trig 为字符串或列表（如 `on: push` / `on: [push]`）-> 无 pull_request_target -> 红

(3) deny-list（存在即红，HC-3）
    命中 paths / paths-ignore / branches / branches-ignore 任一 -> 红
    prt 的键集合 ⊄ {types, paths, paths-ignore, branches, branches-ignore} -> 红（未知键，fail-closed）

(4) types 下界（HC-4）
    键缺失                              -> 绿（官方默认三型 = 同一下界）
    值非列表 / 空列表                    -> 红
    set(值) ⊇ {opened, synchronize, reopened} ? 绿 : 红（并逐项列出缺口）
```

**失败信息要求**：须含**文件名 + 具体键名 + observed 值**，与既有断言的 `::error::` 口径一致（可定位、可复核）。**通过语**：打印一行明确放行语（格式对齐既有两 step，如 `Guard Self-Check: pull_request_target trigger surface intact`）。

#### 8.4.5 四段式线上取证（不可省略、不可合并）

**为何必须「合并后 + 四段」**：`pull_request_target` 读 **base 侧**定义（§8.1）⇒ 新断言 step 在**引入它的 PR 内根本不执行** ⇒ 单 PR + 文档论证**不可证伪**（「必红」与「脚本恒红」无法区分）——#103 的 P0 事故正是此坑（「本地能跑通」≠「GitHub 能跑」，§10.1 ⑥）。

| 阶段 | head commit 构造 | 期望 | 必记录项 |
|---|---|---|---|
| **① 正向绿基线（不可省）** | 仅新增文档探针，**不触碰**任何 workflow | **绿**（且四个 step 全部执行） | head sha / run id / 新 step 通过语 / step 清单 |
| **② 注入 `paths`** | head 侧 `on.pull_request_target` 加 `paths` | **红** | head sha / run id / 判据原文（含 `paths`） |
| **③ 注入 `paths-ignore`** | 同上，键换 `paths-ignore` | **红** | head sha / run id / 判据原文（含 `paths-ignore`） |
| **④ `types: [labeled]`** | 把 `types` 换成 `[labeled]` | **红**（判据须列出 `opened`/`synchronize`/`reopened` 三缺口） | head sha / run id / 判据原文 |

- **② 与 ③ 不得合并、不得任选其一**（issue #116 要求三种写法**全覆盖**）；AC116-3/4（`branches` / `branches-ignore`）若采纳可否决点 1 的四键口径，则**与 ②③ 同轮追加两段**（共六段）；若可否决点 1 被否决则不加段，但须按 §8.4.7 登记边界。
- **注入点 = head 侧 `guard-selfcheck.yml` 自身的 `on` 块**：对既有两断言构成**单变量**（`ci.yml` 未动 ⇒ 既有断言不受牵动）。
- **收尾（零残留）**：trail PR **关而不合** + **删分支** + `gh api` **复算 404**（与 §8.3 / §10.2 ⑥ 同口径）。
- **② 的附加判读**：注入 `paths` 后 base 定义仍无过滤 ⇒ 该 run **照常产生**；若该 commit 上**无 run** ⇒ 判**取证失败**（非「红」），须记录并重新设计注入点。

#### 8.4.6 残留登记（R-A / R-B / R-C，逐字保留，不得淡化）

- **R-A**：本方案使削弱 PR 拿到**红色** Guard Self-Check，但因 Guard Self-Check **当前不是 required**（实测 required 名单只有 `CI Gate`）⇒ **削弱 PR 仍可带红被合入** ⇒ **不声称「阻止生效」**。
- **R-B**：「合并后删掉/改坏这条断言」= **0010 §9 B2 的结构性下限**（任何 CI 都无法自保「自己的定义被合并掉」）⇒ **不得声称闭合 B2**。
- **R-C**：`types` 的 required 语义（check 缺失 vs Pending）**未实测且官方无据**（官方 skip 归因表仅 path / branch / commit message 三种）⇒ **不作为本票任何判据**。

> 补充（R-C 的处置依据）：本票范围**不含 required**（§3.1 Out / §12）⇒ 不产生实测样本 ⇒ R-C 对本决策**无信息价值**（无论该语义成立与否，都不改变「采纳 C1 + 报红」）；实测须真人临时加入 required，有**全局副作用**（所有 PR 被一条未经验证的新 required 卡住）⇒ R-C 由 blocking **降级为 non-blocking 登记项**。

#### 8.4.7 可否决点 1（人工可单点否决）与回滚路径

**可否决点 1（本方案唯一可拆项）**：若不接受 Q3 中 `branches` / `branches-ignore` 的**超纲增补**（issue #116 只要求 `paths` / `paths-ignore` / `types` 三种写法），可把 deny-list **缩回三键**（`paths` / `paths-ignore` + `types` 下界），**其它裁决完全不变**。此时须在 §9.1 边界② 与 §13 R9 **显式登记新边界**：「`branches` / `branches-ignore` 仍可被削弱（本检查不判红）」为**新增 B 类边界**。

**连带接受的约束**：采纳四键 deny-list 等价于接受「**今后永远不得对本检查加任何触发过滤**」（含 `branches` / `branches-ignore`）。**当前无需为此新设逃生舱** —— 本检查**非 required** ⇒ 红**不阻断**（误红可带红合入）；**日后若纳入 required，才必须同时配逃生舱**（§10 第 4 条已登记该待办，属 #104 后继票范围）；逃生舱**不得**以收窄本判据的方式实现。

**回滚路径**：删该 step，或 revert 合并 commit；因本检查**非 required** ⇒ **不产生 required 死锁**（§14 已核）。

#### 8.4.8 时序依赖与行号易腐注

- **时序依赖**：同 §8.1 —— 本 step 的**首张线上实证**须在其**合并入 `main` 之后**另开一次性 trail 取得；引入它的 PR 自身的 `Guard Self-Check` 通过**只能证明「既有断言不回归」**，**不能证明「新 step 在 GitHub 上执行过」**（#114 / #115 先例，§10.2 ①）。
- **行号易腐注（承 0008 §11）**：本节**不以行号**作判据或文档锚点，一律按 **step 名称**定位（`Install PyYAML` / `Assert guard self-coverage intact (base-anchored, no PR checkout)` / `Assert no GitHub expression inside workflow run bodies`）与 **YAML 解析后的语义结构**（`on.pull_request_target.*`）。实现方如遇本节引用的行号与现状不符，**按语义锚定改写**即可，不改判据。

## 9. 自守边界清单（Q3 / AC4）

> 问题：**谁来保护这条检查不被同样的手法绕过？** 逐条列清「缺口 / 本票处置 / 兜底」。

| # | 绕过手法 | 本票处置 | 残留兜底 |
|---|---|---|---|
| B1 | **PR 删掉 / 改名守门文件** `.github/workflows/guard-selfcheck.yml` | **已机制化**：因 `pull_request_target` 定义读自 base，**本 PR 上该检查仍会运行**；其第 1 条断言（守门文件在 head 侧存在）**立即报红** | 若**已合并**（base 侧文件真的没了）⇒ 后续 PR 不再触发它 ⇒ 只能靠**对该 PR 的 review** |
| B2 | **PR 改坏守门文件的判据逻辑**（弱化断言） | **运行语义已隔离**：本运行读 **base 版**workflow ⇒ 当前 PR 的弱化**不影响本次判定** | 若**已合并** ⇒ 弱化版成为新 base ⇒ **只能靠 review**（任何 CI 都**无法**自保「自己的定义被合并掉」） |
| B3 | **直接推 `main`（绕过 PR）** | 本检查仅 `pull_request_target` 触发 ⇒ 不覆盖直推 | ruleset `23927156` 的 **`pull_request` 规则**（E1）与 `deletion` / `non_fast_forward` 规则；**不直推 main** 亦是本仓纪律 |
| B4 | **改 ruleset**（把本检查移出 required / 改 `workflows` rule / 加 `bypass_actors`） | **无法**由 CI 自保（配置面） | **admin-only**；E1 现 `bypass_actors=[]`、`current_user_can_bypass="never"`；**任何人（含 owner）改 ruleset 都留审计** |
| B5 | **commit message 跳过**（`[skip ci]` 等） | **已免疫**：`pull_request_target` 不受 skip 指令影响（§5.2 官方逐字） | —— |
| B6 | **`pull_request_target` 事件自身语义边界** | 运行在 **base 上下文**（`GITHUB_SHA`=默认分支末次提交）⇒ 判定**必须显式读 `head.sha`**（已落实，§6.3）；**不 checkout / 只读权限**规避「pwn request」安全面 | 分支名「形似 SHA」等**可能不触发**「`pull_request_target`」的官方边界（极端），登记于 §13 |
| B7 | **本检查被 PR 重命名 job / 挪行** | **已免疫**：锚定**消费链结构**（`needs.<j>.outputs.<k>` → `steps.<id>`），**不按 job/step 名或行号** | —— |
| B8 | **插入诱饵 `dorny/paths-filter` step 遮蔽真实步骤**（真实步骤之前部署一个同 action、`filters.image` 含基线条目的 decoy） | **已机制化**：判定的锚来自 **base 侧**消费链（`needs.<j>.outputs.<k>` → `steps.<id>`）⇒ decoy（**未被任何 job 输出引用**）不参与判定，真实步骤删行即红（§8.2 A2/A3）；decoy 复用同一 `id` ⇒ 结构歧义 ⇒ fail-closed（A6/A6b） | —— |
| B9 | **产出链被「重构」**：把消费点搬到等价的新 job、改名产出 job/step、改写 `needs` / `outputs` / `if` 表达式，或改写产出节点的**执行性字段**（`if` / `needs` / `strategy` / `continue-on-error` / `uses` / `concurrency`）或**输出接线**（产出 job `outputs.<K>` / 产出 step `with` 非 `filters` 键），使其**运行期恒不执行**或**输出取不到值** | **已机制化判红**：head 相对 **base 锚**任一「不削弱」断言不成立（产出侧 (a) 结构 / (b) 条目 / (c)(a') 执行性字段 / (e) 输出接线）⇒ **红并要求人工介入**（§8.2 A5/A5b/A5p/A5pb/A16/A17/A37/A38、**X1–X4 / X8 / E1 / E4**） | **有意取舍**：把「静默绿」换成「**可见红**」（宁严勿松）；合法重构 ⇒ 需人工确认。与 `0008 §11` **回滚路径 2** 的关系见 §10 |
| B10 | **fork PR 的 `?ref=<fork-sha>` 可解析性**：`head_sha` 在 fork PR 下是 **fork 内提交**；`GET /repos/{base}/contents/{path}?ref={sha}` 能否解析 fork 提交**待实测** | **未实测**：本仓当前**无 fork 工作流**（用同仓 `ci/**`、`test/**` 分支）⇒ 影响低 | fail-closed（404 ⇒ 红，**非假绿**）；若确不可解析，处理方向 = 经 `refs/pull/<N>/head` 读取（登记为后续加固候选） |
| B11 | **消费节点（门禁本体 `image-guard`）被削弱**（第 3 轮新增面）：整删该 job / 改名、改其 `if` 恒假、删其 `needs`、给其 step 加 `if:false`、加 `continue-on-error`、改其 `strategy` / `uses` / `concurrency` | **已机制化判红**：消费侧 (d1) 存在 / (d2) 消费 job **执行性字段** / (d3) 各 step **执行性字段** 相对 **base 锚**逐条比对，任一不成立即红（§8.2 A23a/b/c、A24b、A24c、A27、A28、A30、A36、A39、A41、**X7 / E2 / E3**） | 与 B9 同：合法等价重构（改名 / 等价改写 `if`）⇒ 需人工确认（宁严勿松，当前非 required 不阻断） |
| B12 | **产出 job `outputs.<K>` 输出接线被削弱**（第 4 轮新增面）：改写 / 删除 `changes.outputs`、把 `outputs.image` 改为常量 / 空串 / 改指其它（不产 `image` 的）step；或给产出 step 加 `continue-on-error` / 改 `with` 的非 `filters` 键（`list-files` 等） | **已机制化判红**：产出 job `outputs.<K>`（base 每个键）**归一后**须与 base 相等；产出 step 的 `continue-on-error` 与 `with` 非 `filters` 键须与 base 相等（§8.2 **X1 / X2 / X3 / X4 / X8 / E4**） | 与 B9/B11 同：合法等价改写（如 `outputs` 用下标写法）⇒ 归一后等价 ⇒ 不红；真实内容变化 ⇒ 需人工确认 |
| **B13** | **本检查自身触发面被削弱**（v1.6 / #116 新增面）：给 `guard-selfcheck.yml` 自己的 `on.pull_request_target` 加上 `paths` / `paths-ignore` / `branches` / `branches-ignore`（**缩小**触发面 ⇒ 被过滤掉的 PR 上本检查**根本不产生 run**，与 §10.1 ①「从未运行」同型），或把 `types` 换成不含默认三型的集合 | **PR 阶段已机制化判红**：新 step「触发面未被削弱」断言（**§8.4**）对 head 侧本文件自身 `on` 块取**四键 deny-list + `types` 下界**（HC-1–HC-5）⇒ 削弱 PR 在本 PR 上即拿红 | **残留 R-A**：本检查**非 required** ⇒ **带红仍可合入** ⇒ **不声称「阻止生效」**；残留 **R-B**：合并后删掉 / 改坏本断言 = **B2 结构性下限**，**不声称闭合 B2**。**可否决点 1**：`branches` / `branches-ignore` 可拆回**（缩回三键），届时须按 §8.4.7 登记「该两键仍可被削弱」为新增 B 类边界。**时态**：本行「已机制化判红」为**规范态**，指 #116 build PR 落地并完成取证后（见 **§8.4 头「时态声明」**） |

**一句话**：**B1/B5/B7/B8/B9/B11/B12 已机制化闭合（均为 #103 轮既成事实）；B13（本检查自身触发面，仅「PR 阶段报红」一侧）已机制化闭合 —— 属**规范态**，指 #116 build PR 落地并完成取证后，见 §8.4 头「时态声明」**；B6/B10 为如实登记的边界；B2/B3/B4 是任何 CI 自守检查的**结构性下限**（merge 后定义 / 配置面 / admin 面），本票**如实登记**、由 review 与 ruleset 兜底，**不假装能自保**。此外 **A25（`ci.yml` 的触发面：`on.pull_request.paths`）仍属 B 类边界 + 另立票**（§9.1 边界② / §13 R9）——**#116 不覆盖它**（证据见 §6.2.1 末「范围澄清」）。**本检查的（静态）闭合面 = §6.2.1 定义的封闭字段集合（执行性 + 输出接线 + 触发面）**。

### 9.1 威胁模型边界（D1-B）

**本检查的（静态）闭合面 = §6.2.1 定义的封闭字段集合（执行性 + 输出接线）内的守门链削弱一律红**——删条目 / 搬消费点 / **产出或消费 job·step 的任一执行性字段被削弱**（`if` / `needs` / `strategy` / `continue-on-error` / `uses` / job `concurrency`）/ **产出 job `outputs.<K>` 接线被改·删·改指** / **产出 step `with` 非 `filters` 键被改** / 结构不可识别 / 文件缺失 / 语法坏（§8.2 A1–A22、A23–A30、A36–A41、**X1–X4、X8、E1–E4**）。**注意（第 4 轮收窄）**：旧的「静态上可见的守门链削弱**一律**红」全称表述已被 **X1–X4** 证伪，现收窄为「**上述封闭集合内**一律红」；集合外字段至多产生**可见红**、不产生静默零覆盖（封闭性论证见 §6.2.1）。

**不在本票闭合面内**（如实登记，不假装能自保）：

1. **仅运行期才可判定的失效**——例如某输入语义（或 dorny 过滤语义）使 `filters.image` **恒不匹配**、或守门链运行期因外部条件被跳过；**§6.2.1 的封闭集合是静态面，无法判定运行期语义** ⇒ **另立票**，不夸大本检查能力。
2. **触发面削弱 —— 分两侧处置（v1.6 更新）**：
   - **(2a) 本检查自身触发面（`guard-selfcheck.yml` 的 `on.pull_request_target`）= 已由 #116 闭合「PR 阶段报红」**：新增断言 step（**§8.4**）取**四键 deny-list + `types` 下界**，削弱 PR **在本 PR 上即拿红**（自守边界见 **B13**）。**残留**：非 required ⇒ 带红仍可合入（**R-A**）；合并后可删断言属 **B2 结构性下限**（**R-B**）⇒ **不声称「阻止生效」、不声称「已闭合触发面」**。
   - **(2b) `ci.yml` 自身触发面（A25）= 仍为 B 类边界 + 另立票，#116 不覆盖**：如 `on.pull_request.paths`（正向白名单）排除 `.github/workflows/ci.yml` ⇒ 改 `ci.yml` 的 PR **不再触发** `ci.yml` ⇒ `image-guard` 根本不跑 ⇒ 守门对该 PR 零覆盖（且 `ci-gate.yml` 的 `CI Gate` 为自包含 always-run 令牌，**不代偿** image-guard）。**本检查仍判绿**。**不覆盖的证据**：`ci.yml` 当前 `on.pull_request` **已含** `paths-ignore: ["docs/**","**/*.md","LICENSE",".gitignore"]`（本仓合法基线）⇒ 若把 #116 的四键 deny-list 套到 `ci.yml`，**当前 `main` 会立即恒红** ⇒ 该扩展在**先设计例外白名单**之前不可行 ⇒ 归**另立票**。**注意**：本处 A25 指「**`ci.yml` 的触发面**」，与 issue #116 正文所称「A25」（指本检查自身 `types` 被改成 `[labeled]`）**编号冲突、指代不同**（编号冲突见 §13 R9 表后脚注）。裁决理由（沿用原裁决）：
   - ① 本票命题的锚定面是 **job/step 消费链**；`on` **事件级触发过滤**属**另一轴（触发面）**；
   - ② `0008` §3.1 / §13 与 `0010` §3.1 已把 **`paths-ignore` / 触发面治理列为 Out**；
   - ③ 若把 **`ci.yml` 的触发面**纳入，闭合面将**无界**（会延伸到 ruleset、平台配置；且 `ci.yml` 现基线已含合法 `paths-ignore` ⇒ 须先设计例外白名单，见 §6.2.1 末「范围澄清」）；
   - ④ 但仍**如实登记**该缺口：闭合它属**独立机制**，与 ruleset `workflows` rule **同属「触发 / 配置面」加固** ⇒ **另立票**（本规范不实现）：**#119（架构 / 配置面）或另立 follow-up 票**。
3. **架构级解法（后续加固候选，本票只登记、不实现）**——① ruleset 的 **`workflows` rule** 指定 base 侧 workflow（需 admin）；② 把 `image-guard` 迁到 **`pull_request_target`**。
4. **step 内容面（`run` 正文 / `with` 参数 / step 顺序）**：消费 job 的 step 把 `Build image via compose` 的 `run` 改成 `echo skip`、或改写消费 step 的 `with` ⇒ **本检查判绿**（**有意取舍**）：这类改动**弱化覆盖但 check 仍产出**（非静默零覆盖），且无法静态判定其语义 ⇒ **不纳入** §6.2.1 的封闭集合，**归 code review（可拒绝）**。**边界澄清（第 4 轮）**：消费 / 产出 step 的 **`uses`（含版本）已纳入执行性集合**（§8.2 **A37/E3**，改变了第 3 轮 `(d4)` 仅比 `if` 的口径）；本条**仅**涵盖 `run` 正文 / `with`（产出 step 的 `with` 非 `filters` 键除外，见 §6.2.1）。

> 该边界与 §13 **R8 / R9** 一致：**本检查把「静默绿」转成「可见红」，但不宣称能判定一切运行期语义，也不覆盖触发 / 配置面与 step 内容面**。

## 10. 假红处置（Q2）

**张力**：0008 §11 **回滚路径 2** 明确定义「删除白名单自守条目」为**合法回滚操作**（退回「改 `ci.yml` 的 PR 不自证」）⇒ 本检查会**挡住**一个合法操作 ⇒ 形式上是**假红**。

**逐条处置（结论不留空）**：

1. **先定性**：被挡下的红**不是**「误报」，而是**「把原本为零的证据变为可见信号」** —— 缺口本身正是「删除动作零证据」；本检查让**任何**删除都留下红色痕迹，**这正是目的**，不是副作用。
2. **现状不阻断**：本票**不把它设为 required**（required 构成归 #104，E1 现 required 仅 `CI Gate`）⇒ 当前它是**非阻断信号**，合法回滚**照常可合入**（人工确认即可）。故张力当前为**潜在**、非激活。
3. **落地约定**：合法走「回滚路径 2」时，PR 应**就地更新 0008 §11** 并说明触发本检查的原因（本检查的失败信息已给出去向：`whitelist entries weakened: [...] missing from filters.image of ...`）——把「静默删除」转为「**带说明的、被记录的删除**」。
4. **若日后纳入 required（#104）**：必须**同时**设计**显式的、人工可核的逃生舱**（如：指定 label / PR body 指令 + 人工审批），**不得**直接收窄 required；**逃生舱设计属 #104 范围，本票只登记、不实现**。
5. **明确反对**：「检测到本 PR 连带改了任意 docs 文件就自动放行」——该判据**可被轻易伪造**（附一个空文档即可绕过），**违背本检查的初衷**，**不采用**。
6. **合法「守门链 / 消费节点重构」触发红（第 2/3/4 轮新增面，见 B9/B11/B12）**：把消费点搬到等价新 job / 改名 job/step / 等价改写 `if` / 换 action 版本 / 消费 job 删步 / **改写产出 `outputs.<K>` 或产出 step `with` 非 `filters` 键** 会**判红**（§8.2 A5/A5b/A5p/A5pb/A16/A17/A37/A38、A23/A24/A27/A28/A30/A36/A39/A41、**X1–X4/X8/E1–E4**）。
   - **定性**：同第 1 条——**不是误报**，而是**把「静默等价重构」显式化为需人工确认的信号**；静态无法区分「等价转移」与「转移 + 禁用」，故**宁严勿松**。
   - **介入路径**：当前**非 required** ⇒ 重构 PR **照常可合入**（人工确认「链条等价 + 无禁用」即可）；若日后纳入 required（#104），应与「回滚路径 2」**共用同一逃生舱**（指定 label / PR body 指令 + 人工审批），**不单独另立**。

**登记**：见 §13 **R2**（回滚路径 2）、**R8**（威胁模型边界）与 **R9**（触发面 / A25）。

### 10.1 本次 P0 事故记录：workflow 被判 Invalid ⇒ 检查从未运行（#103）

> 本节为**已发生事故的如实归档**（非计划），是本规范「为何必须新增一条**静态护栏**」的直接来源。为与 ⑤ 的护栏口径一致，本节凡 GitHub 表达式一律以「`$` + 双花括号」转写，不写原始字面量。

**① 事故现象**：已合并的 `.github/workflows/guard-selfcheck.yml` 一度是**无效 workflow 文件** ⇒ 该检查**从未运行过一次**。GitHub 侧表现为（**事故窗口内的快照**）：本 workflow 的运行**均为 `push`** 事件（该窗口内共 6 次）、运行的 `name` 显示为**文件路径**（而非 `Guard Self-Check`）、**无 job**、`conclusion=failure`。该 failure **不是**判定逻辑报红，而是**文件被判 Invalid、根本未实例化任何 job** ⇒ 本检查在此期间的**所有 PR 上均零覆盖**（比假绿更隐蔽：连 `pull_request_target` run 都从未产生）。**现状计数（本次回填复算）**：截至本次回填，`guard-selfcheck` workflow 共 **13** 次运行 = **9 次 `push`**（`name` 显示为文件路径 `.github/workflows/guard-selfcheck.yml`、无任何 job、`conclusion=failure`）+ **4 次 `pull_request_target`**（`name=Guard Self-Check`）；修复合并后 `push` 事件**不再产生**此类幽灵运行。

**② 根因**：GitHub 会对 step 的 `run:` 正文做**表达式插值**后才交给 shell，且**不认 shell / Python 注释语法**。正文里任何「`$` + 双花括号」序列（哪怕写在注释里）都会被当作 GitHub 表达式求值；表达式非法 ⇒ **整份 workflow 文件被判 Invalid、永不运行**。本次事故是两处注释里写了 `steps.<id>.outputs.<key>` 这类**非法表达式**（`.` 后跟 `<` 触发 `Unexpected symbol: '.outputs.'`）。

**③ GitHub 报错原文**：

```
Invalid workflow file: .github/workflows/guard-selfcheck.yml#L1
(Line: 70, Col: 14): Unexpected symbol: '.outputs.'.
Located at position 11 within expression: steps.<id>.outputs.<key>
```

**④ 修复**：PR **#113**（commit `66ac8f7`）移除 `run:` 正文内的非法表达式字面量 → 合并 commit **`a141e8d`**（现 `main` HEAD）。

**⑤ 新增的静态约束（本票 Part A 护栏）**：`guard-selfcheck.yml`（及本仓全部 workflow）的 `run:` 正文内**不得出现** GitHub 表达式字面量（形如「`$` + 双花括号」）；需要插值的量一律经 `env:` 传入。**规则口径 = 有意的「全禁」策略（非缺陷）**：连合法的 `run` 内插值也一并报红 —— 因本地**无法可靠复现 GitHub 侧表达式解析器**（无法判定任意表达式是否合法），故保守侧优先防「整份 workflow 被判 Invalid ⇒ 静默零覆盖」；**替代写法 = 经 `env:` 传值**，而**不是**放宽本规则（该策略不得日后以「误红」为由收窄）。该约束由本文件内**新 step**「Assert no GitHub expression inside workflow run bodies」**机检**，规则与 fail-closed 行为：

1. 经 `gh api "repos/{REPO}/contents/.github/workflows?ref={HEAD}"` **列举 head 侧**全部 `.yml` / `.yaml`；
2. 逐文件经 `gh api -H "Accept: application/vnd.github.raw"` 取**原文**（**不 checkout PR**、不执行 PR 代码，与既有设计一致）；
3. `yaml.safe_load` 后**仅**遍历 `jobs.*.steps[*].run` **正文**检查目标标记；标记在脚本内**拆开构造**（`"$" + "{{"`）⇒ 护栏**自身**的 run 正文不含该字面量；
4. 命中 ⇒ 打印 `::error::` 并列出 **文件 / job / step 名 / run 内行号 / 该行原文**，`exit 1`；
5. **fail-closed**：API 失败 / 目录列举为空 / 文件 YAML 解析失败 / 无 `jobs` 映射 ⇒ 一律报红并给出可定位信息；全绿时打印 `Guard Self-Check: no GitHub expression inside workflow run bodies`。

**覆盖边界（显式登记）**：本护栏**只**覆盖 `run` 正文；**其它会被 GitHub 插值的字段**（`name` / `env` 的**值** / `if` / `with` 等）中的非法表达式**不在本护栏范围内**，**仍可致整份 workflow 被判 Invalid 而本护栏不报红** ⇒ 本护栏**不声称**「整类 workflow Invalid 已闭合」（其闭合面**仅限 `run` 正文**）。护栏本体即 `guard-selfcheck.yml` 内的该 step（**仓库内自包含**，不依赖任何未纳库的本地辅助脚本）。风险登记见 §13 **R10**。

**⑥ 事故为何能躲过 4 轮评审 + 终审（方法论缺口）**：全部验证手段都是 **PyYAML 解析** + **本地把内嵌 Python 当脚本跑**（§8.2 的等价打桩）—— **没有任何一轮模拟 GitHub 侧的表达式插值**。即 **「本地能跑通」≠「GitHub 能跑」**；评审把「脚本语义正确」误当作「workflow 文件合法」。**教训**：对 workflow 文件的验证必须覆盖 **GitHub 侧的解析 / 插值语义**——本票的静态护栏（⑤）即为此缺口的补强。

**⑦ 护栏本地自测（等效打桩，随本票执行）**：抽取新 step 的内嵌判定脚本，桩替换 `subprocess.run`（假 `gh`）后喂入 6 场景：① 真实仓（干净）⇒ 绿；② `run` 正文含字面量 ⇒ 红并给定位；③ 字面量仅在 `env:`（非 `run`）⇒ 绿（证只扫 run 正文）；④ 空目录 ⇒ 红（fail-closed）；⑤ YAML 坏 ⇒ 红（fail-closed）；⑥ 无 `jobs` 映射 ⇒ 红（fail-closed）。**6/6 通过**。

### 10.2 新护栏 step 的线上首跑实证（trail PR #115，源于 PR #114）

> 本节回填 **§10.1 ⑤ 新增的静态护栏 step**（`Assert no GitHub expression inside workflow run bodies`）在 GitHub 上的**首次真实执行**证据。数据由主理人一次性取证（trail PR **#115**），本节**如实照录、不新增判断**。为与全文口径一致并避免复制致 workflow Invalid，本节凡 GitHub 表达式一律以「`$` + 双花括号」转写，不写原始字面量。

**① 为何必须「合并后」取证（结构性时序边界）**

守门自守检查由 **`pull_request_target`** 触发（§5.1 / E4），其 workflow 定义**读自 base 侧、不读被测 PR 的 head** ⇒ **对 `guard-selfcheck.yml`（守门自身 workflow）的任何改动，都无法在引入它的那个 PR 内自测**：`pull_request_target` 在该 PR 上运行的仍是 **base 侧旧定义**（不含新 step）。因此 **PR #114 自身的 `Guard Self-Check` 通过，只能证明「既有检查不回归」，不能证明「新 step 真的在 GitHub 上执行过」** ⇒ 必须在 **#114 合并（merge commit `b801404`）之后**另开一次性 trail 取得首张实证。此即本票**已登记的结构性边界**（与 §8.1「trail 实测不能在 PR-1 自身上完成」同型）。

**② trail 设计（单变量）**

| 项 | 值 |
|---|---|
| trail PR | **#115**（一次性取证；`base = main`） |
| 分支 | `test/114-runbody-guard-trail` |
| 内容 | 一份**文档探针** + **一次注入**（注入点 = `.github/workflows/ci-gate.yml` 的 `run:` 正文） |

**注入点选 `ci-gate.yml` 的理由**：它**不在守门链内**——既有断言 `assert_head_not_weakened` 锚定的是 **`ci.yml`**——故阶段②对**新护栏 step**构成**单变量**测试（注入 `ci-gate.yml` 不牵动既有断言）。

**③ 阶段①：正向绿基线（未篡改任何 workflow）**

| 项 | 值 |
|---|---|
| head commit | `eb6f6a7` |
| `Guard Self-Check` | **pass**（7s） |
| run id | `36017095374` |
| 该运行实际执行的 step | `Set up Python` / `Install PyYAML` / `Assert no GitHub expression inside workflow run bodies` / `Assert guard self-coverage intact (base-anchored, no PR checkout)` |
| 新 step 的通过语 | `Guard Self-Check: no GitHub expression inside workflow run bodies` |

⇒ 证明「环境可跑通 + 新 step 在 GitHub 上**确实被执行**且**可放行**」（排除「脚本在 runner 上根本跑不起来 ⇒ 恒红」的不可证伪态；与 §8.3 正向绿同一必要性）。

**④ 阶段②：注入必红**

向 `ci-gate.yml` 的 `run:` 正文注入 **1 行**注释形态的 #103 原始非法表达式（即「`$` + 双花括号」包裹 `steps.<id>.outputs.<key>`）：

| 项 | 值 |
|---|---|
| head commit | `d35eae5` |
| `Guard Self-Check` | **fail** |
| run id | `36017282513` |

**判据（引用的报错输出；其中内嵌表达式按本规范口径转写为「`$` + 双花括号」）**：

```
##[error]run body contains a GitHub expression literal
##[error].github/workflows/ci-gate.yml [job=gate] step 'Always-run required gate': GitHub expression literal in run body at run-body line 2 :: # trail 取证注入（#114 阶段②）：「$」+ 双花括号 包裹 steps.<id>.outputs.<key> —— #103 事故的原始形态
Guard Self-Check FAILED (run-body expression lint):
 - .github/workflows/ci-gate.yml [job=gate] step 'Always-run required gate': ...
##[error]Process completed with exit code 1.
```

> **必读（复制风险）**：上述回显行在原报错输出中**含一段 GitHub 表达式字面量**；本节已按全文口径转写，其余照录。**该字面量形态（「`$` + 双花括号」序列）不得出现在任何 workflow 的 `run` 正文中——它正是本护栏所拦截的 #103 事故形态**，一旦写入会使**整份 workflow 被 GitHub 判 Invalid、永不运行**（§10.1 ② / R10）。

**⑤ 两个附带确认（同一 trail 的观测量）**

1. **顺序符合设计**：阶段②该运行的 step 清单**只有新护栏 step**——既有断言 step（`Assert guard self-coverage intact (base-anchored, no PR checkout)`）**未执行** ⇒ 新 step **先失败**并把 job 停在 `exit 1`，**顺序符合设计**。
2. **同 SHA 上 `CI Gate` 缺席**：`d35eae5` 上 **`CI Gate` 未产生 check**——因 `ci-gate.yml` 本身被注入**弄成非法 workflow**，GitHub **不再为其产出** check；这是 §10.1 ①「非法 workflow ⇒ 检查从未运行」在 `ci-gate.yml` 上的**同型再现**（非本护栏判定所致）。

**⑥ 收尾：零残留**

trail PR **#115** 按惯例**关闭而不合并**（`state = CLOSED`、`mergedAt = null`），其分支已删除并经 `gh api` **复算远端 ref 为 404** ⇒ **零残留**（与 §8.3 / AC5 同口径）。

**⑦ 本节能力边界（如实登记，不夸大）**

本实证证明的是：**§10.1 ⑤ 的新护栏 step 在 GitHub 上确实被执行、且对「`run` 正文出现表达式字面量」这一 #103 事故形态**报红**（含 fail-closed 链路）。其**闭合面仍仅限 `run` 正文**——**不**覆盖 `name` / `env` 值 / `if` / `with` 等其它会被插值的字段（§10.1 ⑤、R10），**不**声称「整类 workflow Invalid 已闭合」。

## 11. 新 required context 的顺序死锁（Q4）

**问题**：0009 §6.3 的教训 —— 新增 required check **须先在 base 上成功注册过**（「先注册、后收窄」），否则触发 E5「required check 须近 7 天内成功完成过」的资格问题 / 死锁。**是否适用于本检查？**

**结论**：

1. **当前不适用**：本票**不把** `Guard Self-Check` 加入 required（required 构成归 #104，E1 现 required 仅单条 `CI Gate`）⇒ 无「新 required context」⇒ 顺序死锁**不触发**。
2. **若 #104 纳入 required**：**适用**，且本检查**天然满足资格前提** —— `pull_request_target` **在** E5 的合规事件清单内 ⇒ 只要 workflow **已合并入 base** 并在某次 PR 上**成功过**，其 check 即可被 required 评估；执行序列仍须遵循 0009 §6.3「**先注册（合并本 workflow）→ 后收窄 required**」。
3. **附加前提**：本检查**无 `paths` / `if` / `needs`**（恒定上报），**不落** E5 的「workflow 因 path 过滤被跳过 ⇒ 停 Pending」陷阱；但其**唯一 job 会因判定失败而红**（这是设计，非缺陷）⇒ 作为 required 时，**异常 PR（删条目 / 坏语法）会被真正阻断**，这正是 AC1 想要的。

## 12. 文件级修改清单（相对路径）

| 类别 | 文件 | 变更 |
|---|---|---|
| **新增（本票）** | `.github/workflows/guard-selfcheck.yml` | 自守 workflow（§6 骨架）：`pull_request_target` + REST 读 **base（可信锚）/ head（被测）** + 守门链「不削弱」断言 + 自守断言 + **`run` 正文表达式护栏**（#103 P0 事故护栏，见 §10.1 ⑤） |
| **新增（本票）** | `docs/specs/0010-ci-guard-selfcheck.md` | 本规范（自带范围声明 / AC / 自守边界 / 回滚路径 / 行号易腐注） |
| **改（本票）** | `docs/specs/0010-ci-guard-selfcheck.md` §8.1 / §8.3 / §10.1 / §7（AC）/ §13（R10） | 回填 trail 自举实测（run id / 判据原文，§8.3）；新增 §10.1 P0 事故归档 + 静态护栏说明；AC1/AC2/AC5 更新为实测结论；新增 R10 |
| **改（v1.6 / #116 规范轮）** | `docs/specs/0010-ci-guard-selfcheck.md` | ① 头部状态追加 **v1.6** 段（含措辞红线）；② §3.1 Out 更新触发面处置与票号；③ §6.2.1 增**第三类：触发面** + 封闭性论证 + 「范围澄清」（#116 不覆盖 A25 的证据）；④ 新增 **§8.4**（#116 可执行交付规范：方案与组合边界 / AC116-1–12 / HC-1–10 / 判定口径 / 四段式取证 / 残留 R-A·R-B·R-C / 可否决点 1 / 时序与行号易腐注）；⑤ §9 新增 **B13** + 一句话更新；⑥ §9.1 边界② 拆为 (2a)/(2b)；⑦ §13 **R9 改写** + 新增 **R11–R13**；⑧ §16 更新 follow-up 票号 |
| **不改（本轮）** | `.github/workflows/guard-selfcheck.yml` | 本轮**只写规范**；#116 的新断言 step 与其四段式线上取证由 **#116 的 build PR** 承担（§8.4 / §16） |
| **不改** | `.github/workflows/ci.yml` | `changes` 判据（`filters.image` 条目集合）与 `image-guard` 契约断言 step 一字不动（AC3） |
| **不改** | `.github/workflows/ci-gate.yml` / `qodana_code_quality.yml` | 无 |
| **改（D3，仅 1 行）** | `docs/specs/0009-ci-required-status-gate.md` §10 R4 | 追加**一行回指**：本项已由 `0010` 落地（issue #103 / PR #111）。**只此一行，不动 0009 其它段落** |
| **不改** | `docs/specs/0008-*.md` | 无（其口径改写属 #100 步4 / #104；同步归 #100 步4，避免撞车） |
| **不改** | `docs/architecture.md` | 无（规范索引同步归 #105） |
| **不改** | ruleset `23927156` | 无（required 构成归 #104，真人执行） |
| **不改** | Java 源码 / `pom.xml` / `Dockerfile` / `docker-compose.yml` / `core-contracts` | 无 |

**数据清理范围**：无。**表结构变更与迁移脚本**：无。

### 12.1 文档落点选择理由（本票择 (a) 新增 0010）

任务卡允许 (a) 新增 `docs/specs/0010-<slug>.md` **或** (b) 就地增补 0008 / 0009。**本票择 (a)**，理由：

1. **避撞车**：0009 §9 已把 `0008` 列为 **#100 步4（PR-2）** 的改写目标（§7/§1 的「CI 绿」口径 v3）；此时就地改 0008 会与 #100 的既定写面**冲突**。新增 0010 与任何票解耦。
2. **命题独立**：本检查是**新增的独立 artifact**（新 workflow + 新判定语义 + 新自守边界），值得**自带的范围声明 / AC / 自守边界 / 回滚**章节（0008 §13 / 0009 §13 亦声明「另开独立 build 票」）。
3. **可回溯**：0010 以「承接 0008 §10 R2 / 0009 §10 R4」的开头显式登记依赖，不丢失到 0008 的引用链。

> **v1.6 追加（为什么不另起 `0011-*`）**：#116 与 #103 的 P0 护栏（§10.1）**同属一个 artifact**（`.github/workflows/guard-selfcheck.yml`）的加固子票，且本仓已有**先例** —— #103 的护栏与其 trail 实证即落成 0010 内的 §10.1 / §10.2（而非另起新号）。故 #116 亦落为 0010 内新增章节 **§8.4**，并同步改写 §6.2.1 / §9 / §13 / §16。**若主理人裁定须独立成篇**，建议文件名 `docs/specs/0011-ci-guard-trigger-surface-hardening.md`（并把 §8.4 整体迁出、在 0010 留一行回指）；本版按「先更新 0010」的默认执行。

## 13. 风险与残留未知

| 编号 | 项 | 分级 | 处置 |
|---|---|---|---|
| **R1** | **门禁结构变更（含合法）判红**：重命名 `filters.image` 键、换掉 `dorny/paths-filter`、**重构消费链**（`needs` / `outputs` / `if` 写法变更）、**改写产出 / 消费节点的执行性字段**（`if` / `needs` / `strategy` / `continue-on-error` / `uses` / `concurrency`）、**改写产出 `outputs.<K>` / 产出 step `with` 非 `filters` 键**、**消费 job 被删 / 改名 / 删步** ⇒ 本检查判红 | `non-blocking` | 红即**要求 review**（门禁结构变更本应被看见）；当前非 required，不阻断。**准确描述（第 4 轮修正，取代第 3 轮）**：判红覆盖**四类** —— ① **定位不到**（结构不可识别 ⇒ fail-closed；**下标写法 / 空白重排已在假红外排除**，仅余真实结构变更）② **定位到但被削弱**（产出侧 (a)(b) 结构 / 条目 / **(c)(a') 执行性字段** / **(e) 输出接线**，A1/A2/A3/A5q/A37、**X1–X4/X8/E1/E4**）③ **产出侧运行期被禁用 / 消费点被搬走**（(d)，A5/A5b/A5p/A5pb/A16/A17/A38）④ **消费节点被削弱**（消费侧 (d1)–(d3)：删 / 改名门禁 job / 改执行性字段 / 删 step，A23/A24/A27/A28/A30/A36/A39/A41、**X7/E2/E3**）。**「挪行」「插入诱饵 step」已在假红外排除**（B8）；「改名 job/step」**现属第④类会红**（宁严勿松，见 B9/B11/B12） |
| **R2** | **合法回滚（0008 §11 路径 2）触发红**（Q2） | `non-blocking`（本票不阻断） | 见 §10：合法回滚**照常可合入**（非 required）；若 #104 纳入 required，须**先**设计逃生舱（登记为 #104 待办） |
| **R3** | **B2/B3/B4 结构性下限**：merge 后定义 / 配置面 / admin 面无法自保（Q3） | `non-blocking` | 如实登记（§9），由 **review + ruleset** 兜底；**不假装能自保** |
| **R4** | **`pull_request_target` 的极端不触发边界**：官方说明「形似 SHA 的分支名可能不触发本事件」 | `non-blocking` | 本仓分支命名（`ci/**`、`test/**` 等）不落该模式；登记备查 |
| **R5** | **并行 API 抖动的假红**：`gh api` 遇瞬时错误 ⇒ 本检查 fail-closed（读不到即红） | `non-blocking` | 本票**有意 fail-closed**（守门宁严勿松）；失败信息含 `api_err` 便于区分 404 / 瞬时错误；重跑即可清 |
| **R6** | **任务卡 1.5 与实测不一致**：卡记 required=`[]`，实测 required=`[CI Gate]`（E1） | 信息 | 本票**不涉 required**，不影响交付；已在 §4.2 E1 登记，并**上报主理人** |
| **R7** | **依赖 `pyyaml`**：workflow 内**显式** `actions/setup-python@v5`（固定 `python-version: "3.12"`）+ `pip install --quiet "pyyaml==6.0.3"`（runner 网络依赖） | `non-blocking` | **已消除隐式依赖**（不再依赖 runner 自带 PyYAML 这一未声明假设）；`pyyaml` 为纯 Python 小包、成熟稳定；固定版本以保确定性；若安装失败 ⇒ 显式噪声（与「删行必红」的混淆源已由三段式 trail 的正向绿分支排除，§8.3） |
| **R8** | **威胁模型边界（D1-B，第 4 轮收窄；v1.6 微调）**：本检查（静态）闭合面 = **§6.2.1 的封闭字段集合（执行性 + 输出接线 + 触发面）内**的削弱一律红（**不**是全称「静态上可见的削弱一律红」——该全称表述已被 X1–X4 证伪）；**仅运行期才可判定的失效**（如过滤语义使 `filters.image` 恒不匹配）、**`ci.yml` 的触发面（A25）**、**架构 / 配置面**（ruleset `workflows` rule / `image-guard` 迁 `pull_request_target`）**仍不在闭合面内**（其中「本检查自身触发面」已由 #116 纳入，见 B13 / §8.4 / R9 (2a)） | `non-blocking` | 如实登记（§9.1 ①②③）：运行期失效 ⇒ **另立票**；`ci.yml` 触发面 ⇒ **另立票**（R9 (2b)，#119）；架构级解法 ⇒ 后续加固候选，本票**只登记、不实现** |
| **R9** | **触发面削弱（分两侧，v1.6 改写；两侧判据主体是不同文件，不可互相代偿）**：**(2a) 本检查自身触发面** —— 判据主体 = head 侧 `guard-selfcheck.yml` 的 `on.pull_request_target`；被加 `paths` / `paths-ignore` / `branches` / `branches-ignore`，或 `types` 不含默认三型 ⇒ 被过滤掉的 PR 上本检查**根本不产生 run**（与 §10.1 ①「workflow Invalid ⇒ 从未运行」同型）；**(2b) `ci.yml` 触发面（A25）** —— 判据主体 = `ci.yml` 的 `on.pull_request`；`paths` 正向过滤排除 `.github/workflows/ci.yml` ⇒ 改 `ci.yml` 的 PR 不触发 `ci.yml` ⇒ `image-guard` 零覆盖；本检查判**绿**（**属已裁决的 B 类边界，非假绿**） | `non-blocking`（**已裁决**） | **(2a) 已由 #116 闭合「PR 阶段报红」**：新断言 step（**§8.4**，四键 deny-list + `types` 下界，读 head 侧 `SELF_PATH`）+ 自守边界 **B13**；**残留 = R-A（非 required ⇒ 带红仍可合入，ruleset 实测 required 仅 `CI Gate`）** ⇒ **不声称「阻止生效」**；**时态**：本 (2a) 为**规范态**，指 #116 build PR 落地取证后（见 §8.4 头「时态声明」）；**可否决点 1**：`branches` / `branches-ignore` 可缩回（§8.4.7），届时登记为新 B 类边界。** (2b) 维持「B 类边界 + 另立票」，#116 不覆盖**：#116 的新断言**不读** `ci.yml` 的 `on` 块；且 `ci.yml` 现基线已含合法 `paths-ignore: ["docs/**","**/*.md","LICENSE",".gitignore"]` ⇒ 套用四键 deny-list 会令**当前 `main` 恒红** ⇒ 该扩展在**先设计例外白名单**之前不可行 ⇒ 归 **#119（架构 / 配置面）或另立 follow-up 票**，本规范不实现；已在 §3.1 / §6.2.1 末 / §9.1 / §16 登记 |
| **R10** | **workflow 文件被判 Invalid ⇒ 检查静默零覆盖**（#103 P0 事故的**类别**）：`run:` 正文内出现 GitHub 表达式字面量（形如「`$` + 双花括号」）⇒ GitHub 对其求值、非法即令**整份 workflow 无效、永不运行**（run 全为 `push`、`name`=文件路径、无 job、`conclusion=failure`）⇒ 本检查**对所有 PR 零覆盖**（比假绿更隐蔽：连 `pull_request_target` run 都不产生） | **已机制化**（本票 Part A 新增静态护栏） | **新 step**「Assert no GitHub expression inside workflow run bodies」机检本仓全部 workflow 的 `run` 正文（fail-closed：API 失败 / 空目录 / YAML 坏 ⇒ 红）；6/6 本地自测通过（§10.1 ⑦）。**覆盖边界（显式登记）**：本护栏**只**覆盖 `run` 正文；**其它会被 GitHub 插值的字段**（`name` / `env` 的值 / `if` / `with` 等）中的非法表达式**不在其范围内**，**仍可致 workflow 被判 Invalid** ⇒ 本护栏**不声称**「整类 workflow Invalid 已闭合」。事故归档 / 根因 / **方法论缺口（无一轮验证模拟 GitHub 侧表达式插值 ⇒「本地能跑通」≠「GitHub 能跑」）** 见 **§10.1** |
| **R11**（= R-A） | **#116 残留 R-A**：削弱 PR 拿到**红色** Guard Self-Check，但 Guard Self-Check **当前不是 required**（实测 required 名单只有 `CI Gate`）⇒ **削弱 PR 仍可带红被合入** | `non-blocking`（**登记残留**） | **不声称「阻止生效」**；「阻止生效」一侧归 **#119 / 另立 follow-up 票**（§8.4.6）。若日后 #104 后继票把本检查纳入 required ⇒ 须**同时**设计显式、人工可核的**逃生舱**（§10 第 4 条），且按 §8.4.7「今后永远不得加任何触发过滤」的口径，**纳入 required 时才须配逃生舱** |
| **R12**（= R-B） | **#116 残留 R-B**：「合并后删掉 / 改坏这条触发面断言」= **§9 B2 的结构性下限**（任何 CI 都无法自保「自己的定义被合并掉」） | `non-blocking`（**登记残留**） | **不得声称闭合 B2**；由 review 兜底（B2 既有处置） |
| **R13**（= R-C） | **#116 残留 R-C**：`types` 的 required 语义（check 缺失 vs Pending）**未实测且官方无据**（官方 skip 归因表仅 path / branch / commit message 三种） | `non-blocking`（**由 blocking 降级**） | **不作为本票任何判据**。降级依据：本票范围不含 required ⇒ 不产生实测样本 ⇒ 对本决策无信息价值；实测须真人临时加入 required，有**全局副作用**（所有 PR 被一条未经验证的新 required 卡住）（§8.4.6 补充） |

> **编号冲突脚注（v1.6 新增，防误读）**：issue #116 正文把「把 `types` 改成 `[labeled]` ⇒ 本检查不再触发」称作「#103 审查项 A25」；而本规范 §8.2 打桩表的 **A25** 指「`ci.yml` 的 `on.pull_request` 加 `paths`」（= 本 R9 的 **(2b)**）。**二者编号冲突、指代不同对象**。本规范（含 R9 / §9.1 边界② / §16）中的 **A25 一律取后者**；**不得**据 issue #116 正文的 "A25" 字样判定 (2b) 已被 #116 覆盖。

**blocking 未知点：0。**

## 14. 回滚 / 可否决与契约影响

- **回滚（一行级，最简）**：**删除** `.github/workflows/guard-selfcheck.yml` + 删除本规范（或将本规范 §14 标「已回滚」）。**注意**：因本 workflow **未进 required**（E1 required 仅 `CI Gate`），单独删它**不会**产生「required 指向不存在 check」的新死锁 —— 与 0009 §11 回滚 2 的情形不同（那条 gate 是 required）。
- **降级（保留文件、关掉判定）**：如需临时停用而不删文件，可把 job 的 `if` 置 `false`（job 被跳过 = 报 Success，E5）——**属应急，不推荐**（会静默化，与初衷相反，仅在确认误报时短暂使用）。
- **真人否决权**：真人可否决本方案（含改选 ruleset `workflows` rule 路线）；若改选，须重出 DecisionRecord。
- **契约影响声明**：**零** `core-contracts` 字段 / 方法签名变更，不触 `AGENTS.md`「core 改动即中止」闸门。改动面 = **CI workflow 文件 + 文档**。

## 15. 需同步的文档（建议，本轮不写）

| 文档 | 建议变更 | 归属 |
|---|---|---|
| `docs/specs/0009-*.md` §10 R4 | 加一行回指「本项已由 `0010` 落地（issue #103 / PR #111）」 | **已完成**（本票 D3，第 1 轮修复；仅此 1 行） |
| `docs/specs/0008-*.md` §10 R2 处置列 | 加一句「残余边界 ① 已由 0010 承接」 | 后续文档票 / #100 步4（改 0008 会撞 #100 写面，本票**不动**） |
| `docs/architecture.md`「规范索引」 | 补入 `0010`（及 0008 / 0009） | **#105** |
| `README.md` | 如需说明「守门自守」检查，可同步 | 建议，非必须 |

（本规范仅**提出建议**，不代替写；除 `0009` §10 R4 一行回指（本票 D3）外，其余归后续票。）

## 16. 明确不做的事（范围外）

| 项 | 归属 |
|---|---|
| 改 `ci.yml` 的 `changes` 判据 / `image-guard` 运行时契约断言 | **禁止**（AC3 硬约束） |
| 把本检查设为 required / 改 ruleset `23927156` | **#104（真人执行）** |
| `docs/architecture.md` 规范索引同步 | **#105** |
| 三件套 `paths-ignore` 治理（C4） | 0009 备选，**不做** |
| **本检查自身触发面加固**（`guard-selfcheck.yml` 的 `on.pull_request_target` 过滤键） | **#116 本规范已给出交付规范**（§8.4：AC116-1 – AC116-12 / HC-1 – HC-10 / 四段式取证）；**实现与取证由 #116 的 build PR 承担**（本轮只写规范，不改 workflow） |
| **`ci.yml` 触发面削弱（A25）**（`on.pull_request.paths` 排除 `ci.yml`；含 ruleset `workflows` rule 类「触发 / 配置面」加固） | **#116 不覆盖**（证据：§6.2.1 末「范围澄清」——`ci.yml` 现基线已含合法 `paths-ignore`，套用四键 deny-list 会令 `main` 恒红）；归 **#119（架构 / 配置面）或另立 follow-up 票**；本规范**登记为 B 类边界、不实现**（§9.1 边界② / §13 R9）。**票号弹性说明**：#119 实测标题为「架构级消除 paths-filter 读 head 检出——ruleset `workflows` rule 或 image-guard 迁 `pull_request_target`」，与本项所需「**`ci.yml` 触发面例外白名单设计**」**题面不同** ⇒ 是否开新票属**主理人 / 用户偏好**，本规范**不代决**，保留「#119 **或**另立 follow-up 票」的弹性写法 |
| 把本检查纳入 required（以闭合 §8.4.6 **R-A**「带红仍可合入」残留） | **另立票**（#104 已 CLOSED，其后继票）；须**同时**设计显式、人工可核的**逃生舱**（§10 第 4 条 / §13 R11） |
| ruleset `workflows` rule 加固 | 可选未来项（需 admin），**本票不做** |
| 任何 Java 源码 / `pom.xml` / `Dockerfile` / `docker-compose.yml` / `core-contracts` 改动 | **禁止**（命中即中止并上报） |
| 顺手「修」0008 / 0009 口径或索引 | **禁止**（撞车 #100 步4 / #104 / #105） |
