# Workflow Labels（workflow 标签）

本文件承载本 repo 的 **workflow state label 集**，供 `issue-update-label` 等 skill 查找「role → 实际 label 字符串」的映射。

**不含 triage** —— triage 集（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）由 `triage-labels.md` 管，本文件不涉及。

**managed 区**：从本 H1 到第一个 `## Annotations` H2 之前的所有内容，由 `/setup-workflow-labels` 拉取仓库现存 label 集整体覆盖。手写注解请写在 `## Annotations` 之下 —— 该区及之后的内容不会被覆盖。

## priority

| Logical role | Label in our tracker | Meaning |
| --- | --- | --- |
| high | priority/high | |
| medium | priority/medium | |
| low | priority/low | |

## status

| Logical role | Label in our tracker | Meaning |
| --- | --- | --- |
| in-progress | status/in-progress | |
| blocked | status/blocked | |
| on-hold | status/on-hold | |

## review

| Logical role | Label in our tracker | Meaning |
| --- | --- | --- |
| needs-review | review/needs-review | |
| in-progress | review/in-progress | |
| approved | review/approved | |
| changes-requested | review/changes-requested | |
| awaiting-author | review/awaiting-author | |
| needs-rereview | review/needs-rereview | |
| blocked | review/blocked | |
| on-hold | review/on-hold | |
| draft | review/draft | |
| fixing | review/fixing | |
| verifying | review/verifying | |
| fix-blocked | review/fix-blocked | |
| iteration-limit | review/iteration-limit | |

## Unclassified

以下 label **没有 prefix**，未被任何 prefix 纳管。如需纳管，请手工编辑本文件把它们归到合适 prefix 下（或先在仓库端补上 prefix）：

| Label in our tracker | Meaning |
| --- | --- |
| accessibility | |
| bug | |
| documentation | |
| duplicate | |
| enhancement | |
| good first issue | |
| help wanted | |
| invalid | |
| question | |

## Annotations

<!-- 在这里写你的手写注解：本区及之后的内容不会被 /setup-workflow-labels 覆盖。
     典型用途：说明某个 role 在本 repo 的特殊语义、迁移约定、或标注弃用的 label。 -->
