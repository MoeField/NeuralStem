# 文档索引

## 当前有效文档

- [DEVELOP.md](DEVELOP.md) — 构建、架构、关键不变量、常见坑、测试与发布。
- [ARCHITECTURE.md](ARCHITECTURE.md) — 播放/分离/持久化不变量与被否决方案。
- [UI_STATE_MAP.md](UI_STATE_MAP.md) — 全部可操作控件与状态之间的依赖/影响对照表。
- [STEP_HISTORY.md](STEP_HISTORY.md) — 各阶段/版本改了什么、为什么改、怎么验证。
- [LICENSING.md](LICENSING.md) — MIT / NCM / AVS3 参考实现边界与禁止提交清单。
- [AV3A_INVESTIGATION.md](AV3A_INVESTIGATION.md) — AV3A/Audio Vivid 的 FFmpeg、OpenHarmony、Media3、仓库与许可调查记录。
- [REMEDIATION_STATUS.md](REMEDIATION_STATUS.md) — **整改看板**：v0.2.60 REVIEW 闭环、NEED-CONFIRM 结论、剩余风险与发布门禁。
- [V0.3.0_PLAN.md](V0.3.0_PLAN.md) — v0.3.0 结构重构方案：AudioSink 第一刀 + 后续服务/VM 拆分。
- [REVIEW_CONVENTIONS.md](REVIEW_CONVENTIONS.md) — 代码/架构评审约定、漏检复盘与发版门禁。
- [STABILITY_TEST_PLAN.md](STABILITY_TEST_PLAN.md) — 状态模型、操作序列、故障注入、错误分类与发布门禁。
- [STABILITY_REPORT_v0.2.65.md](STABILITY_REPORT_v0.2.65.md) — 2h soak + 长分离 + PCM 租约验证（含 SeparationService 常驻 bug 修复）。
- [STABILITY_REPORT_v0.2.55.md](STABILITY_REPORT_v0.2.55.md) — v0.2.55 稳定性测试实跑记录（历史版本报告）。

## 历史归档

[archive/](archive/README.md) 保存过程文档/历史快照：审计、维修计划、阶段验收、长跑、维护审计、已完成的状态模型设计。  
这些文档**不代表当前状态**；当前状态以上面的有效文档为准。

- [archive/PATH_AUDIT.md](archive/PATH_AUDIT.md) — v0.2.14 路径审计（BUG 已后续修复）。
- [archive/REPAIR_PLAN.md](archive/REPAIR_PLAN.md) — 维修计划（已完成/合并）。
- [archive/ACCEPTANCE_V0.2.15.md](archive/ACCEPTANCE_V0.2.15.md) — v0.2.15 验收记录（当时未完整通过）。
- [archive/PCM_SHARED_DECODE.md](archive/PCM_SHARED_DECODE.md) — PCM 共享解码阶段记录。
- [archive/PCM_ACCEPTANCE_2026-11-16.md](archive/PCM_ACCEPTANCE_2026-11-16.md) — v0.2.20 PCM 验收 + v0.2.21 附记。
- [archive/SOAK_2026-11-13.md](archive/SOAK_2026-11-13.md) — v0.2.18 10h 长跑。
- [archive/MAINTENANCE_AUDIT.md](archive/MAINTENANCE_AUDIT.md) — 2026-11-18 维护审计。
- [archive/STATE_MODEL.md](archive/STATE_MODEL.md) — 分离状态模型 M0–M4 设计（已完成）。

项目使用说明见仓库根目录的 [README](../README.md)。
