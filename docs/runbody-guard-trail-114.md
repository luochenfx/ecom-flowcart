# run 正文表达式护栏 线上自举探针（PR #114 / issue #103，一次性）

仅用于观测 `guard-selfcheck.yml` 新增护栏 step（`Assert no GitHub expression inside workflow run bodies`）的线上行为，采集完证据后随分支一并删除，**不合并**。

- 阶段① 正向绿基线：未篡改任何 workflow，期望 `Guard Self-Check` = **pass**，且新增护栏 step **确实执行**（跑的是 base 侧脚本）。
- 阶段② 注入必红：向 `ci-gate.yml` 的 `run:` 正文注入 #103 事故的原始非法表达式，期望 `Guard Self-Check` = **fail** 且由新增护栏 step 定位报错。
