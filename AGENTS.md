# AGENTS.md

## Agent skills

### Issue tracker

Issues 与 specs 存放在 GitHub Issues，所有操作通过 `gh` CLI。见 `docs/agents/issue-tracker.md`。

### Triage labels

五个 canonical triage roles 使用默认 labels：`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`。见 `docs/agents/triage-labels.md`。

### Domain docs

Single-context 布局：repo 根目录 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。

## 审查闸门（review gate）

审查意见的分档、PR 描述只留三块、非决策层意见累计 2 轮封口、计数断言必须绑 SHA 与命令 —— 四条成文于 `docs/agents/review-gate.md`。

**本节刻意与 `## Agent skills` 同级，而非它的子节**：`## Agent skills` block 由 `/setup-matt-pocock-skills` 整体替换内容，塞进去的指针会在重跑时被清掉。

## 契约变更闸门（core 改动即中止）

**管什么**：`core-contracts` 的**字段 / 方法签名变更** —— record 组件增删改、方法签名、`schemas/**` 结构。

**不管什么**：注释、javadoc 级同步。改文档字符串不算契约变更，可随当前票直接改。

**怎么办**：命中则**停下**，先另立 Spec 票，先 Spec 后 Build；落地由独立 build 票承担。
