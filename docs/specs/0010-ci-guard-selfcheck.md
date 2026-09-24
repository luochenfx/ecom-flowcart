# 规范：CI 守门自守检查（不读被测 PR 自身检出的白名单下界断言）

> 来源：GitHub issue [#103『Build: 守门不可自守删除自己——设计不读 PR 自身检出的检查』](https://github.com/luochenfx/ecom-flowcart/issues/103)（label `ready-for-agent`）
> 依赖：[规范 0008（CI 镜像构建守门）](./0008-ci-image-build-guard.md) §10 R2（残余边界 ①「删除自己」/ ②「语法级失效」）、§11（回滚路径 2「白名单行删除」）、§13；[规范 0009（CI required status check 门禁）](./0009-ci-required-status-gate.md) §10 R4、§13（均声明本项**另开独立 build 票**）
> 状态：**v1.1（实现期 + 第 1 轮审查修复）** —— 落地自守 workflow 本体 + 本规范；第 1 轮审查后：锚定面改为**消费链**（闭合诱饵遮蔽假绿）、**显式**安装 PyYAML、收窄权限、补自守边界 B8 与三段式（正向绿→删行红→语法坏红）trail 计划、补 `0009` §10 R4 一行回指；trail 自举实测须待 workflow 合并入 base 后执行（见 §8 时序依赖）。
> 范围声明：本规范**只**解决「镜像守门的路径白名单**不再自守自己**」这一命题（#94-① 残余边界）。**不改** mirror 守门的判据与运行时契约断言（AC3），**不设/不改任何 required check**（归 #104），**不碰** `docs/architecture.md`（归 #105）。详见 §3 与 §16。

## 1. 决策概览

- **问题一句话**：`ci.yml` 的 `image-guard` 路径白名单由 `dorny/paths-filter` 从**被测 PR 自身的检出**读取 ⇒ 某 PR 只要删掉白名单里的自守条目 `- '.github/workflows/ci.yml'`，该 PR 自身就不再被 `image-guard` 覆盖（且按官方语义「job 被 `if` 跳过 = Success」）⇒ **删除动作本体零 CI 证据**（0008 §10 R2 ①）。让 `ci.yml` YAML 语法失效则 workflow 整体不触发，同样零证据（残余边界 ②）。
- **采纳方案：新增独立自守 workflow（`pull_request_target` + REST 读 head，语义锚定下界断言）**。
  1. **新增** `.github/workflows/guard-selfcheck.yml`：触发用 **`pull_request_target`**（workflow 定义读自 **base/默认分支** ⇒ PR 改不动检查本体）；
  2. **判定不读被测 PR 的检出**：全程**不 `actions/checkout`**、不执行任何 PR 代码，只经 **GitHub REST API** 读 `head_sha` 侧的 `ci.yml`，解析后按**语义锚定**断言「`filters.image` 条目集合 ⊇ 基线 `{'.github/workflows/ci.yml'}`」；
  3. 同一 job 内附**自守断言**：本 workflow 文件在 head 侧仍须存在（防「删除/改名守门本体」，AC4 的机制化）。
- **语义锚定（Q1）**：锚定 **消费链语义结构** —— 由下游 job 的 `needs.<j>.outputs.<k>` 反查「**真正驱动门禁**」的那条 `dorny/paths-filter` 步骤（步骤 id 由该 job 的 `outputs.<k> = ${{ steps.<id>.outputs.* }}` 解出），再取该步骤 `filters.image` 的条目集合；**不按**「首个同名 action」、**不按** job / step 名、**更不按**行号（行号易腐，承 0008 §11 注）。见 §6.2。
- **基线是硬编码于 base 侧本 workflow 的期望集合**（PR 不可篡改），observed 来自 head 侧 REST 读 ⇒ 消除「判据随 PR 检出漂移」的根因。
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
| Out | mirror 守门判据 / 运行时契约断言的任何改动（AC3）；required 构成（#104）；`docs/architecture.md`（#105）；`paths-ignore` 治理（C4，0009 备选）；镜像内容架构 |

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
| E6 | 本票**本地自举单测**（11 场景，`subprocess.run` 打桩喂入 fixtures；第 1 轮审查后扩至覆盖诱饵遮蔽与结构不可识别） | 正向绿→pass；删白名单行→fail；**诱饵 step 遮蔽→fail（修订后闭合）**；外壳 / 结构不可识别→fail；守门文件被删→fail。见 §8.2 | 高（本地实测） |

> **行号锚定注（承 0008 §11 / 0009 §4.3）**：本规范**不以 `ci.yml` 行号**作判定或文档锚点，一律按 **action 名 / job / step 名称 / YAML 解析后的语义结构**定位。本 workflow 的判据同样**不读行号**。

## 5. 决策结论与被拒候选

### 5.1 选定：`pull_request_target` + REST 读 head + 语义下界断言

| 准则 | 结论 |
|---|---|
| 抗篡改 | `pull_request_target` 的 workflow 定义读自 **base/默认分支**（E4 逐字）⇒ PR **无法**改本检查本体；且本检查**不 checkout / 不执行** PR 代码，从根上避开「PR 修改检查逻辑」的手法 |
| 反自指 | 基线期望集合**硬编码于 base 侧本 workflow**（PR 不可篡改）；observed 经 **REST 读 `head_sha`** ⇒ 「判据」与「被测检出」解耦 |
| 覆盖语法失效 | observed 由 **REST 直接取原始字节**，与 `ci.yml` 是否可被 GitHub 解析**无关** ⇒ 外层 YAML 坏掉也能读到并判红（E6：`brokenouter` 场景） |
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
- 单 job（`name: Guard Self-Check`），**三步**：① `actions/setup-python@v5`（固定 `python-version: "3.12"`）→ ② `pip install --quiet "pyyaml==6.0.3"`（**显式**获取判定依赖，消除「隐式依赖 runner 自带 PyYAML」的假设）→ ③ bash → `python3`（判定脚本）。无 `paths` / `paths-ignore` / `if` / `needs`（恒定上报）；
- **不 `actions/checkout`**；判定全程经 `gh api`（用 `GITHUB_TOKEN`）读 head 侧文件。

### 6.2 判定锚定面（Q1）

**锚定面 = 消费链语义结构**，四层：

1. **消费点锚**：扫描 `ci.yml` 各 job 的 `if` 表达式，收集形如 `needs.<J>.outputs.<K>` 的引用 ⇒「被消费的 job 输出」即门禁消费点（本仓 = `image-guard` 的 `if: needs.changes.outputs.image == 'true'`）；
2. **产出步锚**：由被消费 job 的 `outputs.<K> = ${{ steps.<ID>.outputs.* }}` 解出**产出步骤 id**（本仓 = `changes` job 的 `steps.filter`）——**不按 job / step 名、不按行号**；
3. **输入锚**：该产出步骤须以 `dorny/paths-filter` 为 action；读其 `with.filters`（内嵌 YAML 字符串），`safe_load` 后取 `image` 键的列表；
4. **集合锚（下界）**：断言每个门禁产出步骤的 `image` 条目集合 ⊇ 基线 `{'.github/workflows/ci.yml'}`（多个门禁产出步骤时**逐条**断言，任一缺失即红）。

**为何不是「首个同名 action」**：若按「首个 `uses` 以 `dorny/paths-filter` 开头的 step 命中即返回」，PR 只需在**真实步骤之前**插一个 decoy `dorny/paths-filter` step（其 `filters.image` 含基线条目）即可**遮蔽**真实步骤的删行 ⇒ 假绿。改按**消费链**定位后，decoy（未被任何 job 输出引用）不参与判定 ⇒ 遮蔽面闭合（见 §9 B8、§8.2 场景 A/A'）。

**失败归因（`if` 表达式解析的健壮性）**：解析只提取 `needs.<J>.outputs.<K>` 与 `steps.<ID>.outputs.` 两个**引用 token**（正则），**不解析**表达式文法 ⇒ 复合条件（`&&` / `||`）、引号包裹、`needs` 多依赖均不影响命中；提取不到 / job 或 step 解析不出 / id 缺失或重复 / action 非 dorny / `filters` 非串 / `image` 非列表等**任一不可识别情形**，一律 `return None` ⇒ 走**显式 fail-closed** 报错（`could not locate ...`），**不**退回「首个同名 action」，也**不**抛裸异常。

**为什么是「⊇ 下界」而非「== 相等」**：白名单**新增**条目（如未来再加第三个 workflow 自守）应**允许**（不假红）；只有**删掉**基线条目才红 ⇒ 下界断言精确表达不变量「自守条目必须仍在」。基线集合现仅含 1 条（本票残余边界的**核心条目**）；扩展基线 = 往 `BASELINE_IMAGE_ENTRIES` 加条目，机制不变。

**覆盖两条残余边界**：① 删条目 ⇒ 集合缺基线条目 ⇒ 红；② `ci.yml` 外层 YAML 坏 ⇒ `safe_load` 抛错 ⇒ 红（REST 取原始字节，不受「GitHub 能否解析」影响）。

### 6.3 判定证据源（不读 PR 检出的落实）

- **不 `actions/checkout`**、**不执行任何 PR 代码**；
- 每条断言均经 `gh api -H "Accept: application/vnd.github.raw" repos/{owner}/{repo}/contents/{path}?ref={head_sha}` 读**原始字节**；
- `head_sha` 取自 `github.event.pull_request.head.sha`（payload），**不**取 `GITHUB_SHA`（该值在 `pull_request_target` 下 = **默认分支末次提交**，E4）。

## 7. 验收标准（AC）

> 对应 issue #103 的 AC①–④ 定稿；全部可判定。

- [x] **AC1（删行必红）**：存在一条检查，在「删掉 `ci.yml` 中 `filters.image` 的自守条目 `.github/workflows/ci.yml`」的 PR 上**必然失败**。——**判定逻辑已闭合**：打桩复算下「朴素删行 / 诱饵遮蔽 / 结构不可识别」均报红（§8.2）；**线上实测待合并后回填**（§8.3，与 AC2 同批）。
- [ ] **AC2（不读 PR 检出自举测试）**：该检查的判定**不依赖被测 PR 自身检出的文件内容**；须有自举测试 —— 构造一个删掉该行（或改坏 workflow 语法）的 PR，观察该检查**仍报错**。——**待合并后回填**（结构性时序依赖，§8.1；三段式计划见 §8.3）。
- [x] **AC3（不改既有守门）**：**不改变**现有镜像守门的判据与其运行契约断言（`changes` 判据 + `Assert runtime contract (non-root + /data writable)` step 一字不动）。——**已闭合**：`git diff main...HEAD -- .github/workflows/ci.yml` 为空。
- [x] **AC4（自守边界）**：明确写清本检查**自身的自守边界**（谁来保护它不被同样的手法绕过）——见 §9。——**已闭合**（§9 B1–B8）。
- [ ] **AC5（零残留）**：自举用的 trail PR **关而未合**、trail 分支**已删**；无遗留新增分支 / PR。——**待合并后回填**（trail 尚未执行）。

## 8. 落地与自举实测

### 8.1 时序依赖（**关键**：trail 实测不能在 PR-1 自身上完成）

`pull_request_target` 的 workflow 定义**读自 base**（E4）⇒ **PR-1 创建时该 workflow 尚不在 base** ⇒ PR-1 自身**不会**触发本检查 ⇒ **trail 自举实测必须在 PR-1 合并入 `main` 之后**、另开 `test/103-*` 分支进行。这是本票的**结构性时序依赖**，故 AC2 的**线上自举实测判据待合并后回填**（登记为 Phase 2/4 后的待办）。

### 8.2 本地自举单测（已执行，方法 + 结果）

在合并前，先以**等价打桩**验证判定逻辑（不打网络）：将 workflow 的判定脚本抽出，用桩替换 `gh api`（喂入 fixtures 的 head 侧文件），跑 **11 个场景**。**第 1 轮审查发现「诱饵 step 遮蔽」假绿后，本表已扩至含诱饵与结构不可识别用例（A / A' / B / D*），并据此把锚定面从「首个同名 action」改为「消费链」（§6.2）。**

| 场景 | head 侧构造 | 期望 | 实测 |
|---|---|---|---|
| E `ok` | `ci.yml` 原样（未改动）+ 守门文件在 | pass | ✅ exit 0，输出 `Guard Self-Check PASSED` |
| C `removed` | 删掉 `- '.github/workflows/ci.yml'` 一行（无诱饵） | **fail** | ✅ exit 1，`whitelist self-entry removed: ['.github/workflows/ci.yml'] not in filters.image` |
| **A**（D1 核心） | 真实步骤**之前**插 decoy `dorny/paths-filter` step（`filters.image` 含基线），并删掉真实步骤的基线条目 | **fail** | ✅ exit 1，同上 `whitelist self-entry removed`（decoy 不再遮蔽） |
| **B** | 插 decoy（含基线），真实步骤基线条目**保留** | **pass** | ✅ exit 0（不得假红） |
| **A'** | decoy 复用 `id: filter` + 真实步骤删基线条目 | **fail** | ✅ exit 1，`could not locate ...`（重复 id ⇒ fail-closed） |
| **D1** | `ci.yml` 顶层 YAML 坏 | fail | ✅ exit 1，`.github/workflows/ci.yml YAML invalid at head ...` |
| **D2** | `ci.yml` 顶层为序列（非映射） | fail | ✅ exit 1，`could not locate ...`（显式 fail-closed，非 `AttributeError`） |
| **D3** | 门禁产出步骤的 `uses` 非 `dorny/paths-filter` | fail | ✅ exit 1，`could not locate ...` |
| **D4** | 门禁步骤 `with.filters` 非字符串 | fail | ✅ exit 1，`could not locate ...` |
| **D5** | `filters.image` 非列表 | fail | ✅ exit 1，`could not locate ...` |
| F `noguard` | 删掉守门文件 `guard-selfcheck.yml` | fail | ✅ exit 1，`guard workflow ... not found at head` |

⇒ **AC1 判定逻辑（删行必红，含诱饵遮蔽面）本地成立**；**残余边界 ②（语法失效 / 结构不可识别，均走显式 fail-closed、无裸 traceback）本地成立**；**AC4 自守断言（守门文件被删即红）本地成立**。线上自举（真实 GHA run）见 §8.1 时序。

（复算方式：抽取 workflow 内嵌 python 判定脚本 → 桩替换 `subprocess.run` 喂入 fixtures 的 head 侧文件 → 逐场景核对退出码。本地环境注：本机 Git Bash 对 shell heredoc 报 `cannot create temp file for here-document`，属**本地沙箱限制**，非脚本缺陷；GitHub `ubuntu-latest` 支持 heredoc。故本地以「抽取脚本 + 打桩 `subprocess.run`」等价执行。）

### 8.3 线上自举实测计划（**待合并后执行**，三段式判据占位）

为避免「脚本在 runner 上根本跑不起来 ⇒ 恒红」与「断言真生效 ⇒ 删行红」不可区分，线上 trail **必须**含**正向绿分支**，构成**三段式**（缺 ① 则 ②③ **不可证伪**）：

1. **① 正向绿基线**（**必要条件**）：在一个**未改动**白名单的正常 PR 上，`Guard Self-Check` 必须为**绿** ⇒ 证明「环境可跑通 + 判定可放行」；**若 ① 从未跑过，「②③ 红」无法区分是「断言真生效」还是「脚本在 runner 上根本跑不起来 ⇒ 恒红」（第 1 轮审查的必答结论）。**
2. **② 删行必红**：在 trail PR 上删掉白名单自守条目一行 ⇒ `Guard Self-Check` 必须**报红**。
3. **③ 语法坏必红**：改坏 `ci.yml` YAML 语法（或造结构不可识别）⇒ `Guard Self-Check` 必须**报红**。

执行要点：

- ①②③ 均基于合并后 `main` 进行（① 可直接在后续任一正常 PR 上观测；②③ 另开 trail PR `test/103-<slug>`）。
- 每次观测记录 **run id** + `gh pr checks` / `gh run view` 实证。
- **无论成败：关 PR（不合并）+ 删分支，并复算 404**（零残留，AC5）。
- 证据回填本节 + issue #103 评论。

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
| B8 | **插入诱饵 `dorny/paths-filter` step 遮蔽真实步骤**（真实步骤之前部署一个同 action、`filters.image` 含基线条目的 decoy） | **已机制化**：判定按**消费链**定位「真正驱动门禁」的步骤 ⇒ decoy（**未被任何 job 输出引用**）不参与判定，真实步骤删行即红（§8.2 场景 A）；decoy 复用同一 `id` ⇒ 结构歧义 ⇒ fail-closed（场景 A'） | 若消费链本身被重构（改名 `needs` / `outputs` / 改写 `if` 表达式）⇒ 定位不到 ⇒ **红并要求 review**（fail-closed，§13 R1） |

**一句话**：**B1/B5/B7/B8 已机制化闭合；B2/B3/B4 是任何 CI 自守检查的**结构性下限**（merge 后定义 / 配置面 / admin 面），本票**如实登记**、由 review 与 ruleset 兜底，**不假装能自保**。

## 10. 假红处置（Q2）

**张力**：0008 §11 **回滚路径 2** 明确定义「删除白名单自守条目」为**合法回滚操作**（退回「改 `ci.yml` 的 PR 不自证」）⇒ 本检查会**挡住**一个合法操作 ⇒ 形式上是**假红**。

**逐条处置（结论不留空）**：

1. **先定性**：被挡下的红**不是**「误报」，而是**「把原本为零的证据变为可见信号」** —— 缺口本身正是「删除动作零证据」；本检查让**任何**删除都留下红色痕迹，**这正是目的**，不是副作用。
2. **现状不阻断**：本票**不把它设为 required**（required 构成归 #104，E1 现 required 仅 `CI Gate`）⇒ 当前它是**非阻断信号**，合法回滚**照常可合入**（人工确认即可）。故张力当前为**潜在**、非激活。
3. **落地约定**：合法走「回滚路径 2」时，PR 应**就地更新 0008 §11** 并说明触发本检查的原因（本检查的失败信息已给出去向：`whitelist self-entry removed`）——把「静默删除」转为「**带说明的、被记录的删除**」。
4. **若日后纳入 required（#104）**：必须**同时**设计**显式的、人工可核的逃生舱**（如：指定 label / PR body 指令 + 人工审批），**不得**直接收窄 required；**逃生舱设计属 #104 范围，本票只登记、不实现**。
5. **明确反对**：「检测到本 PR 连带改了任意 docs 文件就自动放行」——该判据**可被轻易伪造**（附一个空文档即可绕过），**违背本检查的初衷**，**不采用**。

**登记**：见 §13 **R2**。

## 11. 新 required context 的顺序死锁（Q4）

**问题**：0009 §6.3 的教训 —— 新增 required check **须先在 base 上成功注册过**（「先注册、后收窄」），否则触发 E5「required check 须近 7 天内成功完成过」的资格问题 / 死锁。**是否适用于本检查？**

**结论**：

1. **当前不适用**：本票**不把** `Guard Self-Check` 加入 required（required 构成归 #104，E1 现 required 仅单条 `CI Gate`）⇒ 无「新 required context」⇒ 顺序死锁**不触发**。
2. **若 #104 纳入 required**：**适用**，且本检查**天然满足资格前提** —— `pull_request_target` **在** E5 的合规事件清单内 ⇒ 只要 workflow **已合并入 base** 并在某次 PR 上**成功过**，其 check 即可被 required 评估；执行序列仍须遵循 0009 §6.3「**先注册（合并本 workflow）→ 后收窄 required**」。
3. **附加前提**：本检查**无 `paths` / `if` / `needs`**（恒定上报），**不落** E5 的「workflow 因 path 过滤被跳过 ⇒ 停 Pending」陷阱；但其**唯一 job 会因判定失败而红**（这是设计，非缺陷）⇒ 作为 required 时，**异常 PR（删条目 / 坏语法）会被真正阻断**，这正是 AC1 想要的。

## 12. 文件级修改清单（相对路径）

| 类别 | 文件 | 变更 |
|---|---|---|
| **新增（本票）** | `.github/workflows/guard-selfcheck.yml` | 自守 workflow（§6 骨架）：`pull_request_target` + REST 读 head + 语义下界断言 + 自守断言 |
| **新增（本票）** | `docs/specs/0010-ci-guard-selfcheck.md` | 本规范（自带范围声明 / AC / 自守边界 / 回滚路径 / 行号易腐注） |
| **改（本票，仅回填实测）** | `docs/specs/0010-ci-guard-selfcheck.md` §8.3 | 合并后回填 trail 自举实测结果（run id / 输出） |
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

## 13. 风险与残留未知

| 编号 | 项 | 分级 | 处置 |
|---|---|---|---|
| **R1** | **门禁结构重构的潜在假红**：若未来重命名 `filters.image` 键、换掉 `dorny/paths-filter`、或**重构消费链**（`needs` / `outputs` / `if` 表达式写法变更）⇒ 本检查「定位不到」而红 | `non-blocking` | 红即**要求 review**（门禁结构变更本应被看见）；当前非 required，不阻断。消费链锚定已把「改名 job/step」「挪行」「插入诱饵 step」排除在假红外（B7 / B8） |
| **R2** | **合法回滚（0008 §11 路径 2）触发红**（Q2） | `non-blocking`（本票不阻断） | 见 §10：合法回滚**照常可合入**（非 required）；若 #104 纳入 required，须**先**设计逃生舱（登记为 #104 待办） |
| **R3** | **B2/B3/B4 结构性下限**：merge 后定义 / 配置面 / admin 面无法自保（Q3） | `non-blocking` | 如实登记（§9），由 **review + ruleset** 兜底；**不假装能自保** |
| **R4** | **`pull_request_target` 的极端不触发边界**：官方说明「形似 SHA 的分支名可能不触发本事件」 | `non-blocking` | 本仓分支命名（`ci/**`、`test/**` 等）不落该模式；登记备查 |
| **R5** | **并行 API 抖动的假红**：`gh api` 遇瞬时错误 ⇒ 本检查 fail-closed（读不到即红） | `non-blocking` | 本票**有意 fail-closed**（守门宁严勿松）；失败信息含 `api_err` 便于区分 404 / 瞬时错误；重跑即可清 |
| **R6** | **任务卡 1.5 与实测不一致**：卡记 required=`[]`，实测 required=`[CI Gate]`（E1） | 信息 | 本票**不涉 required**，不影响交付；已在 §4.2 E1 登记，并**上报主理人** |
| **R7** | **依赖 `pyyaml`**：workflow 内**显式** `actions/setup-python@v5`（固定 `python-version: "3.12"`）+ `pip install --quiet "pyyaml==6.0.3"`（runner 网络依赖） | `non-blocking` | **已消除隐式依赖**（不再依赖 runner 自带 PyYAML 这一未声明假设）；`pyyaml` 为纯 Python 小包、成熟稳定；固定版本以保确定性；若安装失败 ⇒ 显式噪声（与「删行必红」的混淆源已由三段式 trail 的正向绿分支排除，§8.3） |

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
| ruleset `workflows` rule 加固 | 可选未来项（需 admin），**本票不做** |
| 任何 Java 源码 / `pom.xml` / `Dockerfile` / `docker-compose.yml` / `core-contracts` 改动 | **禁止**（命中即中止并上报） |
| 顺手「修」0008 / 0009 口径或索引 | **禁止**（撞车 #100 步4 / #104 / #105） |
