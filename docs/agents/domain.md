# Domain Docs（领域文档）

Engineering skills 探索 codebase 时，应如何消费这个 repo 的 domain documentation。

## 探索代码库前，先读这些

- repo 根目录的 **`CONTEXT.md`**，或
- repo 根目录的 **`CONTEXT-MAP.md`**（如果存在）—— 它指向每个 context 的一个 `CONTEXT.md`。读取与当前话题相关的每个文件。
- **`docs/adr/`** —— 读取与你即将处理区域相关的 ADRs。在 multi-context repos 中，也检查 `src/<context>/docs/adr/` 中的 context 级 decisions。

如果这些文件不存在，**静默继续**。不要标记缺失；不要提前建议创建。`/domain-modeling` skill（经由 `/grill-with-docs` 和 `/improve-codebase-architecture` 调用）会在 terms 或 decisions 实际被解决时懒创建它们。

## 目录结构

Single-context repo（大多数 repos）：

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

Multi-context repo（根目录存在 `CONTEXT-MAP.md`）：

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← 全系统级 decisions
└── src/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← 该 context 的 decisions
    └── billing/
        ├── CONTEXT.md
        └── docs/adr/
```

## 使用 glossary 的词汇

当你的输出命名某个 domain concept 时（issue title、refactor proposal、hypothesis、test name），使用 `CONTEXT.md` 中定义的 term。不要漂移到 glossary 明确避免的 synonyms。

如果你需要的概念还不在 glossary 中，这是一个信号：要么你正在发明项目没有使用的语言（重新考虑），要么确实存在缺口（为 `/domain-modeling` 记录）。

## 标记 ADR 冲突

如果你的输出与现有 ADR 矛盾，明确指出，而不是静默覆盖：

> _与 ADR-0007（event-sourced orders）冲突 —— 但值得重新讨论，因为…_
