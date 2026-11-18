# 维护审计（2026-11-18）

> 范围：仓库整洁度、文档一致性、F-Droid 自动发布、状态机/死代码、测试基线。
> 目标：只做低风险整理；不改变发布版本、不动 app 运行行为。

## 结论

- 当前 `main` 干净，JVM **471/0**。
- F-Droid 自动发布已实测成功：run `44134749072`，repo 为 `0.2.28 (48)`。
- 文档中的 F-Droid 手动/自动流程已统一；本地 repo key 仍不入库。
- 发现的高优先级风险不是发布坏了，而是**发布缺少 APK 身份校验**，以及**分离进度跳变仍未定位**。

## 已落的整理

| 项 | 处理 |
|---|---|
| F-Droid 文档 | 重写 `fdroid/README.md`，记录自动发布、已验证 run、secrets、恢复流程 |
| 根 README | 增加 F-Droid 自建源入口 |
| 开发文档 | `docs/DEVELOP.md` 增加自动发布说明 |
| 版本历史 | `docs/STEP_HISTORY.md` 记录发布链路变更 |
| init 脚本 | 统一 `keystore.p12`，修掉残留的 `keystore.jks` |
| 隐私 | 移除 tracked 验收/长跑文档中的设备序列号 |
| 死代码 | 删除空的 `markStemsAvailable()` 及其调用；删除未使用的 `pendingBackfillMsOf()` |
| 文档链接 | 本地 Markdown 链接检查：0 个断链 |

## 需要关注的风险

### P1：自动发布没有校验 APK 身份

`.github/workflows/fdroid-publish.yml` 只下载 `app-arm64-v8a-release.apk`，没有检查：

1. APK 的 `versionName` 是否等于 release tag；
2. APK 是否由预期的 App release key 签名。

后果：

- 如果 release asset 放错，F-Droid 源会出现错误版本；
- 如果 Android CI 因缺少 App 签名 secrets 回退 debug 签名，F-Droid 仍可能发布一个老用户无法覆盖安装的 APK。

建议：workflow 增加 `aapt dump badging` 版本校验 + `apksigner verify --print-certs` 证书 SHA-256 校验（预期值可放 repo variable，避免硬编码）。

### P1：分离进度跳变仍未定位

用户实报：快速/edge 分离过程中进度会跳。今天未继续修，避免在没有日志的情况下猜。

建议下次优先抓：

```bash
adb logcat -s SepUI EdgeSession SepRepo SepDomain
```

重点观察：

- `edge 阶段1/阶段2`
- `backfill` 开始/结束
- `CoverageUpdated` 与 `SeparationUiMapper.percent`
- 是否在阶段切换时出现 `coverage` 回退/重置

### P2：状态机仍有旧旁路

- `SeparationDomain.autoSwitchEnabled` 为 true 后，AppShell 仍保留一套旧 effect 分支；当前基本是死路径，但会增加理解成本。
- `SeparationDomain.clearCache()` 存在，但 UI 的清缓存仍走 `SpikeViewModel.clearCache()`；目前靠“多轨时禁用按钮”保证安全。
- `lastStems` 与 Domain `attachment` 仍是两套引用；正常路径已统一，兼容 fallback 仍需保留。

### P2：分支/本地仓库卫生

- 本地 `gh-pages` 指向 `507dbc8`，远端已经是 `1fc4b79`。发布脚本已改为基于 `origin/gh-pages`，所以无功能影响；建议有空 `git branch -f gh-pages origin/gh-pages`。
- 本地仍有较多已合并/实验分支（`spike/*`、`test/*`、`refactor/*` 等），是否删除由维护者决定；本次未自动删。
- `fdroid/local/` 被忽略，含 `keystore.p12`、`config.yml`、repo 与 tmp；继续只作为本机备份/恢复目录。

### P3：验证工具

- 本地没有 `actionlint`/`act`；workflow 已做 YAML 解析 + 本地 fdroid 流程 dry-run。
- CI 里 F-Droid workflow 已实测跑通；下次 release 后再观察自动触发。

## 下次工作顺序

1. 给 F-Droid workflow 增加 APK 版本 + 签名校验（小改动，收益高）。
2. 用真机日志定位分离进度跳变。
3. 再考虑移除 `autoSwitchEnabled` 旧旁路 / 统一清缓存命令。
4. 历史分支清理（需要维护者确认）。

## 基线

- 版本：`0.2.28 (48)`
- JVM：**471/0**
- F-Droid：`https://neuralstem.miku.pub/fdroid/repo/`
- 本地 Markdown 链接：0 断链

## 更新（2026-11-18 早间）

- **P1.1 已修**：`fdroid-publish.yml` 现在校验 release tag == APK `versionName`，并校验 APK 签名证书；不匹配直接拒绝发布。commit `7c2e648`。
- **P1.2 已修**：补前段进度条和文案都改为整体覆盖率（总进度 Y% · 补前段 X%），不再出现 83% → 0%。commit `0eff07d`，发布 **0.2.31 (51)**。
- **发现并修复自动触发问题**：Android CI 用 `GITHUB_TOKEN` 创建 Release 时不会再次触发 `release: published`；改为监听 Android CI 在 `v*` tag 上成功完成。commit `9efa33b`。
- **过渡状态**：v0.2.29 的自建源已用本地脚本更新到 **0.2.29 (49)**；下一次 tag 再验证新的 `workflow_run` 触发器。
- **P2.2 已修**：清缓存成功后 Domain `job.files/lastFinished` 与 PlayerVM `lastStems/波形` 同步失效，reducer 增加 `StemsCacheCleared` 回归测试；发布 **0.2.30 (50)**。commit `2b577fd`。
- **自动发布已完整验证**：run `44210175726` 成功，包含 APK 版本/签名校验；F-Droid 源已到 0.2.31。
- **P2.1 验证结论：保留旧 fallback**。v0.2.31 真机正常 edge 路径是 `SepDomain startEngine` + `PlayerVm.loadMultiTrack`，没有旧 fallback 的 `AppShell edge gate=... 文件模式播`。但 fallback 仍覆盖 Domain bridge 尚未设置的竞态、edge 取消/失败回退；删除收益低、风险高，因此**有意保留**，不再当死代码删除。分支清理仍待维护者确认。
