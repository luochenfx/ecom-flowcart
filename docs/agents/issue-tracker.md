# Issue tracker：GitHub

这个 repo 的 issues 和 specs 存放在 GitHub issues 中。所有操作都使用 `gh` CLI。`gh` 不可用（未安装 / 未认证 / 网络被拦截）时，如实告知用户，不要假装成功。

## 约定

- **创建 issue**：**禁止裸调 `gh issue create`**。必须先按 title 查重、命中即复用（见 [§写操作幂等性](#写操作幂等性硬性要求)）。多行 body 先落盘，用 `--body-file`。
- **读取 issue**：`gh issue view <number> --comments`，并同时取回 labels。
- **列出 issues**：`gh issue list --state open --json number,title,body,labels,comments`，按需追加 `--label` 与 `--state`。
- **评论 issue**：`gh issue comment <number> --body "..."`
- **添加 / 移除 label**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **关闭**：`gh issue close <number> --comment "..."`

Repo 由 `git remote -v` 推断；在 clone 目录内运行时 `gh` 会自动识别。

## Issue 内容卫生：禁止本机绝对路径（硬性要求）

Repo 目标开源、issues 与 comments 公开。**issue body 与 comment 一律不得出现本机本地绝对路径**（如 `F:\...`、`C:\Users\...`）：既泄露本机目录结构，对协作者也无意义。

需要引用 repo 内文件时，以 **repo 根为锚的根相对路径** 书写（相对路径起点 = 本地 clone 根 = 仓库根），分隔符统一正斜杠 `/`：

- ✅ `.workbuddy/research/landscape.md`、`docs/adr/0001-rabbitmq-quorum-as-v1-message-bus.md`
- ❌ `F:/devlopment/projects/ecom-flowcart/.workbuddy/research/landscape.md`、`C:\Users\...`、`/home/...`

发布 body / comment 前自查一遍，不得残留盘符形态（URL 中的 `https://` 除外）：`[A-Za-z]:[\\/]`。发现历史泄漏同样按此改写，不保留"仅供本机回溯"的绝对路径写法。

## 写操作幂等性（硬性要求）

**问题**：当前 sandbox 会对同一条 Bash 命令执行两次（先沙箱、放行后再跑一次）。`gh` 写操作有外部副作用且非幂等，裸调会导致 issue / comment 双倍膨胀。

**铁律：先查后写（pre-check）。禁止「先建再关掉重复项」** —— 后者本身就是膨胀的成因：每双跑一次就多产生一个需要关闭的 issue，issue 号持续被消耗。

创建 issue 时按以下约束执行：

- **title 是去重键，必须确定性**：完全由内容决定，不得包含时间戳、随机数或其他可变成分。
  - ✅ `Spec: 快捷磁贴离线优先保存 + Note 创建幂等`
  - ❌ `Spec: xxx（2026-09-04 12:31）` —— 每次生成都不同，查重永远不命中
- **查重必须实时**：基于 `gh issue list`（REST）的结果在本地精确匹配 title；**不要用 `gh issue search`** —— GitHub 搜索索引有秒级到分钟级延迟，双跑的第二次会漏判。
- **判定只看 OPEN 集合，且必须收敛**：历史 closed 的同名项是既往双跑残留，不参与判定、也不被反复清理，否则命令永远落进兜底分支。
- **命中即复用，绝不新建重复项**：
  - 有 OPEN 同名项 → 用最小号那条；body 一致则不再改动内容（幂等操作如打标除外），body 不同才更新。
  - 只有 closed 同名项 → 按需 reopen 后复用。
  - 一条都没有 → 才 `gh issue create`。
- **解析 `gh` 的 JSON 输出依赖 jq**：执行前先确认本机可用；缺失时降级到等效过滤手段，**流程不中断**，结束时提示安装（安装方式由 agent 自行查阅）。
- **body 比对注意尾随换行**：命令替换取回的内容与文件内容在末尾换行上可能不同，比对前先归一化。

### 其他 gh 写操作的幂等性

| 操作 | 幂等 | 规则 |
|---|---|---|
| `gh issue create` | ❌ | 走上面的约束：先查后写 |
| `gh issue comment` | ❌ | 先拉 `comments` 比对，已有相同内容则跳过 |
| **`gh issue close --comment`** | ❌ | **拆两步**：先按 comment 的查重规则发评论，再 `gh issue close`（不带 `--comment`） |
| `gh issue close` | ✅ | 第二次报 `is already closed`，无害 |
| `gh issue reopen` | ✅ | 同上 |
| `gh issue edit --add-label` / `--remove-label` | ✅ | 重复打标/去标无副作用 |
| `gh issue edit --body-file` | ✅ | 覆盖语义 |
| `gh api --method POST`（sub-issue / dependency 边） | ⚠️ | 先 GET 确认边不存在再 POST；重复添加通常报错而非静默翻倍 |

### 事后复核（第二道网）

跑完任何 `gh` 写操作后，用 `gh issue list --state all` 复核实际结果，确认目标实体**只有一条**、state 与 label 正确。**不要只看命令的标准输出** —— sandbox 双跑时返回的可能只是其中一次。

复核的目的是**确认没翻倍**，不是"发现重复就关闭"。若复核发现翻倍，说明 pre-check 失效，此时才走兜底清理，并且**必须回头修 pre-check**，而不是把清理当成常规流程。

### 适用范围

不只针对 `gh`。任何有外部副作用、非幂等的网络写操作（`gh api --method POST`、远端 webhook、第三方 API 的创建类调用）都需要同样的先查后写。本地文件写入是覆盖语义，无需处理。

## Pull requests 作为 triage 入口

**PRs as a request surface: no.** _（如果这个 repo 把 external PRs 当作 feature requests，则设为 `yes`；`/triage` 会读取这个 flag。）_

设为 `yes` 时，PR 与 issue 共用同一套 labels 和 states，命令换成 `gh pr` 系列：

- **读取 PR**：`gh pr view <number> --comments`；用 `gh pr diff <number>` 取 diff。
- **列出待 triage 的外部 PR**：`gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments`，然后只保留 `authorAssociation` 为 `CONTRIBUTOR`、`FIRST_TIME_CONTRIBUTOR` 或 `NONE` 的（丢弃 `OWNER` / `MEMBER` / `COLLABORATOR`）。
- **评论 / 打标 / 关闭**：`gh pr comment`、`gh pr edit --add-label` / `--remove-label`、`gh pr close`。

GitHub 的 issues 与 PRs 共用同一个 number space，因此裸 `#42` 可能指两者之一——先试 `gh pr view 42` 解析，失败再回退到 `gh issue view 42`。

## 当 skill 要求 "publish to the issue tracker" 时

按 [§写操作幂等性](#写操作幂等性硬性要求) 先按 title 查重：命中就复用并按需更新 body，**不新建重复项**。

## 当 skill 要求 "fetch the relevant ticket" 时

运行 `gh issue view <number> --comments`。

## Wayfinding 操作

供 `/wayfinder` 使用。**map** 是单个 issue，以 **child** issues 作为 tickets。

- **Map**：单个带 `wayfinder:map` label 的 issue，保存 Notes / Decisions-so-far / Fog 三块 body 内容。`gh issue create --label wayfinder:map`。
- **Child ticket**：以 GitHub sub-issue 形式挂到 map 上的 issue（通过 sub-issues endpoint 用 `gh api` 创建）。未启用 sub-issues 时，改用 map body 中的 task list 记录 child，并在 child body 顶部写 `Part of #<map>`。Labels：`wayfinder:<type>`（`research` / `prototype` / `grilling` / `task`）。一旦被 claim，ticket 就 assign 给 driving dev。
- **Blocking**：用 GitHub 的 **native issue dependencies** —— 这是 canonical 且在 UI 上可见的表达方式。用 `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>` 添加依赖边，其中 `<blocker-db-id>` 是 blocker 的数字 **database id**（取法：`gh api repos/<owner>/<repo>/issues/<n> --jq .id`，_不是_ `#number`，也不是 `node_id`）。GitHub 会报告 `issue_dependencies_summary.blocked_by`（只统计 open blockers，即实时生效的闸门）。依赖能力不可用时，回退到 child body 顶部的 `Blocked by: #<n>, #<n>` 行。当所有 blockers 都关闭，ticket 即视为 unblocked。
- **Frontier query**：列出 map 的 open children（`gh issue list --state open`，限定在 map 的 sub-issues / task list 内），丢弃带有 open blocker 的（`issue_dependencies_summary.blocked_by > 0`，或 `Blocked by` 行里存在 open issue）以及已有 assignee 的；按 map 中的顺序取第一个胜出。
- **Claim**：`gh issue edit <n> --add-assignee @me` —— 这是该 session 的第一次写入。
- **Resolve**：`gh issue comment <n> --body "<answer>"`，然后 `gh issue close <n>`，再向 map 的 Decisions-so-far 追加 context pointer（gist + link）。
