# NeuralStem 开发报告

> 周期：**2026-09-24 → 2026-11-26**（约 2 个月，469 次提交）
> 当前版本：**v0.3.1 (301)** ｜ JVM 519/0 ｜ CI + F-Droid 自建源全绿
> 本文按「时间节点 → 技术栈/架构变更 → 进展」组织；细节见 [STEP_HISTORY.md](STEP_HISTORY.md)。

---

## 1. 概览

| 项 | 值 |
|---|---|
| 项目 | 离线 Android 音源分离 + 多轨播放器（Kotlin/Compose/Media3 + ONNX） |
| 首个提交 | 2026-09-24 |
| 首个可发布版 | `alpha-1`（2026-10-18） |
| 版本跨度 | v0.2.0 → **v0.3.1**（67 个版本 tag） |
| 提交数 | 469 |
| 当前测试 | **JVM 519 / 0 失败**；真机稳定性测试 2h × 多次零异常 |
| 发布渠道 | GitHub Releases + F-Droid 自建源（`neuralstem.miku.pub`） |

---

## 2. 阶段时间线

| 阶段 | 时间 | 关键进展 |
|---|---|---|
| **① 骨架与播放器** | 09-24 ~ 10-18 | 状态机骨架、5 tab 导航、DJ 主题、循环/进度/歌单/设置/EQ；`alpha-1` 首个可发布版 |
| **② 分离引擎** | 10-19 ~ 11-01 | 集成 sherpa-onnx（Spleeter/UVR 2 轨、Spleeter 4 轨、Demucs）；边分离边播；PCM 共享解码；`v0.2.0 → v0.2.5` |
| **③ 多轨重构** | 11-01 ~ 11-13 | **多 ExoPlayer → 单 AudioTrack + 自研 StemMixer**；Sonic 变速变调；edge 缓存快路径；分离状态模型 Level 2（`SeparationDomain` M3a/b/c） |
| **④ 审计整改** | 11-13 ~ 11-21 | 两轮审计的 P0/P1 清零；PCM 共享缓存、SAF 重新授权、模型下载续传、JNI 形状校验、CI/供应链门禁；`v0.2.15 → v0.2.54` |
| **⑤ 评审闭环** | 11-22 ~ 11-23 | v0.2.55 稳定性门禁；两轮 REVIEW 修 3 条 P1 阻断 + P1 批次；AGP 9.5.2；`v0.2.55 → v0.2.60` |
| **⑥ REVIEW v0.2.60 闭环** | 11-25 ~ 11-26 | P1-A（缓存命中导出）/P1-B（tracks 竞态）回归修复；P2-1~20 收口；真机 2h soak 发现并修复 **SeparationService dataSync 常驻** bug；`v0.2.61 → v0.2.66` |
| **⑦ 结构重构** | 11-26 | **v0.3.0**：`AudioSink` 抽象 + I2/I3 守护测试；**v0.3.1**：`PlaybackService` 896 → **489 行**，拆出 4 个单一职责类，2h soak 零回归 |

---

## 3. 技术栈演进

| 时间 | 项 | 变化 | 原因 |
|---|---|---|---|
| 10-19 | CI/SDK | 安装 **API 37（预览）**，放弃 compileSdk 36 降级 | 依赖 AndroidX 2026 版本要求 |
| 11-02 | Gradle | 9.7.1 → **9.8.2** | 工具链跟进 |
| 11-02 | AGP | 9.4.1 → **9.5.0** | 工具链跟进 |
| 11-17 | F-Droid | 降级 spike（AGP8/Gradle8/compileSdk36）**未合并** | 官方 buildserver 仅到 API 33，降级收益不抵成本；改走自建源 |
| 11-23 | AGP | 9.5.0 → **9.5.2** | 补丁升级 |

**当前技术栈**

| 层 | 选型 |
|---|---|
| 语言/UI | Kotlin 2.2.10 + Jetpack Compose（BOM 2026.02.01，Material 3） |
| 构建 | AGP **9.5.2** / Gradle **9.8.2** / JDK 17 |
| SDK | minSdk 23 · targetSdk 37 · compileSdk 37 · NDK 30.0.16248370 · CMake 4.1.2 |
| 播放 | Media3 **1.11.1**（ExoPlayer 门面）+ 自研 `StemMixer`（单 AudioTrack） |
| 本地 AI | sherpa-onnx + ONNX Runtime 1.22.0（Spleeter/UVR/Spleeter4/Demucs） |
| 并发 | Kotlin Coroutines 1.11.0（单线程 IO `limitedParallelism(1)` + 单 actor） |

---

## 4. 架构演进

### 4.1 播放：从「多播放器」到「单混音器」

| 时间 | 架构 | 问题/结果 |
|---|---|---|
| ~10-19 | 每条 stem 一个 ExoPlayer/AudioTrack | 轨间起播差 **800–890ms**、4 轨 underrun 中途暂停、位置对齐失效 |
| **11-01** | **单 AudioTrack + 自研 StemMixer**（单渲染线程按统一采样计数混音） | 根除轨间错位；写失败自动重建；曲尾 Sonic 排空 |
| 11-06 | StemMixer 接入 Sonic | 变速变调不改变音高/速度 |
| 11-12 | 防第二次 writer | 关闭超时后不再启动新写者 |
| **11-26** | **`AudioSink` 抽象**（v0.3.0） | 输出层可注入 `FakeAudioSink`，I2（write 失败重建）/I3（曲尾重播）进入 JVM 测试 |

### 4.2 分离：从回调到领域状态机

| 时间 | 架构 |
|---|---|
| ~10-19 | `SeparationRepository` 回调 + 页面本地状态 |
| **11-13** | **`SeparationDomain`**：单 actor + 纯 `SeparationReducer` + `SeparationController`；UI 只下发事件、观察快照 |
| 11-21 | `PlaybackConnection` 从 `PlayerViewModel` 抽出（B1 第一刀） |

### 4.3 服务：v0.3.1 拆分（896 → 489 行）

```text
PlaybackService（489 行：生命周期 / ExoPlayer / SessionCallback / 焦点）
  ├─ PlayerCommandHandler   命令分派 + 全部 handle*（UID 闸仍在 Service 最外层）
  ├─ MultiTrackCoordinator  多轨状态 + load/exit/音量/seek + 单线程 IO
  ├─ EqHost                 EQ 挂载/重试/快照
  ├─ SnapshotPublisher      快照去抖 + extras 合并 + 200ms 进度
  └─ MultiTrackController → StemMixer（AudioSink 可注入）
```

原则：只改结构不改行为；每步独立 commit + 真机 smoke；`current/active` 主线程专属、IO 唯一跨线程写者。

### 4.4 测试与发布

- **测试**：v0.2.20 期 438 → v0.2.4x 475–489 → v0.2.55 508 → **当前 519**；新增 reducer、缓存、导出安全、AudioSink 等守护测试。
- **发布**：GitHub Actions（构建 + 单测 + lint + 签名 + Release）→ F-Droid 自建源自动索引发布；`v0.2.22` 起稳定运行。

---

## 5. 质量指标

| 指标 | 结果 |
|---|---|
| JVM 单测 | **519 / 0** |
| lint | `lintDebug` / `lintVitalRelease` 通过 |
| 稳定性 | v0.2.65：2h 多轨 soak 6666 ops / 0 FATAL / 0 ANR；v0.3.1：**6652 ops / 27 重启 / 0 错误 / `max_unmuted=1`** |
| 真机验证 | 分离（2/4 轨）、边播、混音、EQ、导出、SAF、AVS3/NCM、快速 inmt/exitsmt 往复 |
| 评审 | 两轮 REVIEW 的 P0/P1 全部闭环；P2 仅余有意保留项 |

---

## 6. 关键决策与教训

1. **多播放器不可行** → 单时钟混音（11-01）是播放侧最重要的架构决策。
2. **状态真源必须单点** → `SeparationDomain`（M3）把分离状态从页面/回调里收口。
3. **评审要查「结构」不只查「结果」** → 沉淀 [REVIEW_CONVENTIONS.md](REVIEW_CONVENTIONS.md)：5 张事实表、6 个必问、4 类测试、发版门禁。
4. **修一个 P1 可能引入另一个** → P1-6 的 `complete` 过度修正导致 P1-A（缓存命中导出阻断）、P1-1 的 IO 迁移导致 P1-B（tracks 竞态）；后续用「回归测试 + 真机复验」兜底。
5. **真机长跑能发现静态审查看不到的 bug** → v0.2.65 发现 `SeparationService` 终态后仍常驻 `dataSync` 前台；同设备其他 app 的 `Time limit exhausted` 错误佐证了配额风险。
6. **文档看板会过期** → 引入 `REMEDIATION_STATUS` 作为看板，并要求每次发版同步 STEP_HISTORY/ARCHITECTURE/索引。

---

## 7. 当前状态与下一步

**当前**：v0.3.1 是内外一致的稳定点——代码结构、测试基线、文档、F-Droid 发布全部对齐。

**可选方向**：

1. 停止版本迭代，进行真实使用反馈收集
2. v0.3.2：继续拆 `SessionCallback` / 单轨生命周期（Service 489 → ~350，收益递减）
3. 产品/体验方向（新功能或打磨）
4. 低风险收尾（文档核对、补充测试、SAF 仪器测试）

**建议**：先让 v0.3.1 稳定运行一段时间，再根据真实使用反馈决定是否继续拆结构。
