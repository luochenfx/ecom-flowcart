# AGENTS.md

## Agent skills

### Issue tracker

Issues 与 specs 存放在 GitHub Issues，所有操作通过 `gh` CLI。见 `docs/agents/issue-tracker.md`。

### Triage labels

五个 canonical triage roles 使用默认 labels：`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`。见 `docs/agents/triage-labels.md`。

### Domain docs

Single-context 布局：repo 根目录 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。

## 契约变更闸门（core 改动即中止）

**管什么**：`core-contracts` 的**字段 / 方法签名变更** —— record 组件增删改、方法签名、`schemas/**` 结构。

**不管什么**：注释、javadoc 级同步。改文档字符串不算契约变更，可随当前票直接改（先例：PR #57）。

**怎么办**：命中则**停下**，先另立 Spec 票，先 Spec 后 Build；落地由独立 build 票承担。
