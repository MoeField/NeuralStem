# NeuralStem 交接文档（给新对话/新 Agent 看）

> **项目**：NeuralStem — Android 原生「AI 音轨分离播放器」（EE5415 个人项目）
> **需求文档**：[`doc/guide.md`](doc/guide.md)
> **本文件用途**：5 分钟了解项目现状、约定与下一步。**先读这个，再读其它文档。**

---

## ★★ 当前状态（2026-10-04，先看这里）

> **main = 「状态机线」，就是最新在推的线。本文件其余章节描述的就是它。**
> （旧版顶部那个“大幅回退、已回退到 9-24”的 Banner 已作废 —— 那是**回退当刻**的状态，之后新线已推进到 10-03。）

| 项 | 值 |
|---|---|
| 当前分支 / HEAD | `main` = `74aa265`（2026-10-03）|
| 工作区 | 干净（无未提交改动）|
| 构建 / 单测 | `assembleDebug` ✅ 、单测 **89** ✅（实跑 `testDebugUnitTest` 计数）|
| 另一分支 | `checkpoint-2026-09-29`（`360db84`）—— **更早的另一条线，仅作参考** |

### 为什么有两条线？（回退的真相）

- 2026-09-29 那条线（= checkpoint）**UX 逻辑 bug 偏多**（EQ 拖动/爆音/回弹、分离页误触发、歌单数量不一致…）。
- **用户主动决策**：放弃该线，**另起一条干净进度**，并要求 **额外引入「状态机」机制，从根上减少操作逻辑类 bug 的概率**。
- ⇒ **回退 = 主动的架构升级，不是被动补救。** checkpoint **不是废弃品**，而是**可参考的另一条实现线**。

### 两条线的差异（`git diff --stat main checkpoint` = 74 文件 / +10645 −1505）

| 只存在于 **main**（状态机线） | 只存在于 **checkpoint**（参考线） |
|---|---|
| `SessionState.kt` + `SessionStateMachineTest`（状态机） | `SoundTouchAudioProcessor*.kt` + `cpp/soundtouch/` + `soundtouch_jni.cpp` |
| `values-en/strings.xml`（I18N 框架） | `WaveformCache.kt`（波形提速） |
| `MixScreen`（混音页）、`SettingsRepository` 等 | `QualitySeparationSection.kt`、`CollapsibleCard.kt` |

> ⇒ 两个待办**其实 checkpoint 已做过原型**：R2/bug C（SoundTouch 变速音质）、波形提速（WaveformCache）。
> 需要时可直接从 `checkpoint-2026-09-29` 取参考。

### ★ 新对话冷启动游标（从这里接着干）

- **恢复计划进度**：**Step 0–10 已全部完成** + **批次 4（F5.3/F5.4/I18N框架/F5.2/混音页）已起步**。
- **当前批次**：**批次 2 ✅ / 同族小修 P2 ✅ P3 ✅**；下一批 = **批次 3（分离流程分析，先只分析）**。
- **下一步顺序**：① ~~批次2 文档修正~~ ✅ → ② ~~同族小修 P2 清 waveform/cover + P3 `restoreFromSession`~~ ✅ → ③ **批次3 分离流程分析** ⬅ 当前 → ④ 批次4 收尾（F5.1/I18N）→ ⑤ 批次5（R5/波形提速/分离前台化/SoundTouch）。
- **演示视频门控**：**在“可发布版本”完工前完全不考虑做**（用户明确）。
- **新纪律（必守）**：git **每一步都 commit**；**每完成一小步就追加 `.memory.md` + 更新本游标**；隔几步主动问用户。

> 旧纪律“每完成一步先写 memory”仍在，但**执行改为“高频追加 + 带游标”**（防止断线后重建理解烧大量上下文）。

---

## ★ 恢复检查清单（简版；完整版见 implementation_plan.artifact.md §一）

图例：✅已有(9-24) ｜ 🔁 待恢复(checkpoint) ｜ ❌ 未做

**核心需求（guide）**
- [x] P0 导入/播放暂停/跳转/状态 ✅
- [x] P1 波形渲染/拖动/双色 ✅
- [x] P2 变速变调（0.5–2× / ±12）✅ ；🔁 升级：X 派生区间 + 对数刻度 + 旋钮/竖滑条
- [x] P3 分离（2-stem ✅ / 4-stem ✅）+ 进度/取消/缓存/离线 ✅
- [x] P4 多轨同步 + 各轨音量/静音/独奏 + 联动 ✅
- [x] N3 音频焦点 ✅ / [x] N4 后台播放+通知 ✅ / [x] N5 分离可取消 ✅
- [ ] P5 UX：F5.1 波形叠加 ❌ / F5.2 横屏 ❌ / F5.3 深色 🔁 / F5.4 触感 ❌ / F5.5 小组件 ❌

**会话新增（待恢复）**
- [ ] 导航 IA：底部导航栏 5 项（歌单/播放器/分离/EQ/设置）  ← **Step 1 先做**
- [ ] 主题 DJ 深色（青绿）
- [ ] 循环按钮（顺序/循环歌单/单曲循环）
- [ ] 歌单页（导入/文件夹/排序/队列）
- [ ] 变速范围 / 转盘时长 设置项
- [ ] 黑胶转盘 DeckView + 封面 CoverExtractor
- [ ] EQ 面板 + 独立页（含拖动本地优先/限频/不回弹/首次写入兜底）
- [ ] 导出（WAV）
- [ ] 分离开关卡片（播放页）
- [ ] 分离页重构（作用于当前曲 + Uri→File 桥）← **最后做**

**交付物**：可运行 APK ✅ / 源码 ✅ / README ✅ / 演示视频 ❌

---

---

## 一、一句话现状

**guide.md 的功能需求（P0–P5）已实现，并在真机端到端跑通。**
**阶段 UI（DJ 播放器界面，U0–U7）已完成**（深色主题 / 可折叠模块 / 转盘 / 分轨 / EQ /
分离开关 / 歌单 / 高质量分轨 / 导出）。

> 🔧 **UI 重做（R 系列）已接近完成**：
> **已完成：R1 骨架 / R1+ 进度条 / R1++ 闪根因 / R4 进度解耦 / R2 左栏 / R3 转盘接入 +
> 进度平滑（StuckDetector 排查）/ 歌单·切歌队列化（含多轮细节修正）**（均真机验证）。
> R2 补强了 **bug C：变速变调防抖**（已回退方案 3：AudioTrack 原生；SoundTouch 尝试失败，见 `research_timescale.artifact.md`）。
> **R6 速度范围设置已完成**（单一项 X∈[1.2,4] 线性 step 0.1，默认 1.4；滑块区间 = [1/X, X]；**播放滑块对数尺度（1.0× 居中）、连续无步进**；设置页横向滑块左右各留 12dp；**无线蓝狐真机已验证**）。
>
> 🧭 **导航 IA 重构（批次 1）已完成**（真机验证）：
> 从 Drawer 改为**底部导航栏 5 项**：`歌单 · 播放器 · 分离 · EQ · 设置`；
> **EQ 独立成页**（从播放器卡片+设置开关收敛）；「关于」并入设置；播放器页移除「分离」按钮；歌单类型提示补强。
>
> **下一步（用户已定优先级）**：①文档/记忆修正（当前）→ ②同族小修（audit P2/P3）→
> ③分离流程分析 + EQ 可用性 → ④F5.x + I18N → ⑤R5/波形提速/分离前台化 → 🚪 可发布版本 → 最后演示视频。
> ⚠️ 设备：BF00A serial `TLF2609A004328`（有线）/ `adb-TLF2609A004328-pQOqTm (2)._adb-tls-connect._tcp`（**无线，优先**）。
> 📌 `ui_rework_requirements.artifact.md`（R 需求定稿）**不在仓库中**；转盘/歌单语义见 `doc/.memory.md`。

**已完成**：阶段 V（功能可行性验证）+ **阶段 M（分离模型探索）** +
**阶段 M2（引擎探测 / 模型分组 / 导航壳 / EQ）** + **阶段 M3（多轨+EQ 搬进 Service）**
+ **阶段 M3.5（关键路径实测补强）** + **阶段 M4（多轨 4-stem：验证 + 落地 + 优化）**。

> 📊 **阶段 M4 的全部实测数据表（报告可直接引用）**：见 `m4_evidence.artifact.md`。

**阶段 M4 新结论（多轨 4-stem 能力）**：
- **S1 ✅**：多轨 **seek 后两轨精确对齐**（seek+暂停 0ms 差；播放中 ~200ms 为估计伪影）⇒ **保留现有多实例架构**。
- **S2 ✅（已落地）**：**Demucs(htdemucs) 4 源真机跑通**（Gen3）：单窗与桌面参考 **NCC 0.9996–1.0000**；
  Gen3 探针 1.83× / 端到端（40s）1.02× 实时；内存偏高（~5GB，待优化）。
- **S3 ✅（已落地为可用功能）**：**Spleeter 4-stem**（`Best-Practice/spleeter-4stems-onnx`，4×19.7MB）：
  桌面重建不变量 **−163.5 dB**；真机分离 **2.2×(中端) / 3.67×(Gen3)**、内存与歌长无关、**四轨可播**。
  ⚠️ **不能走 sherpa**（只支持 2 模型 + 硬编码 ReLU）；4-stem 用 ELU，须自建 DSP。
- **结论**：**Spleeter 4-stem 已是可用功能**（导入 → 4 轨分离 → 四轨播放）；
  Demucs 定位旗舰机音质档，待 Gen3 实测。

**新增能力（M2/M3/M3.5）**：
- **应用导航壳（底部导航栏 5 项）**：歌单 / 播放器 / 分离 / EQ / 设置（“关于”并入设置）
- **设备 benchmark**：首启探测各模型**本机**速度 → 按 RTF 分三档（⚡/⏱/🐢）
- **设置页**：设备能力 + 三档模型分组 + 默认引擎（仅实时档可选）+ EQ 开关
- **EQ（多段均衡）**：平台 `Equalizer`；**单/多轨均可用**，且在 `PlaybackService`（后台存活）
- **模型元数据 + 修 Inst 系反标签**（`ModelSpec` + `stemOrder`）
- **M3：多轨 + EQ 均搬进 `PlaybackService`** —— 单/多轨行为一致：
  后台继续播 + 媒体通知 + EQ 共用一套（**v2 架构**：session 永远认真实 `singlePlayer`）
- **M3.5：关键路径已实测** —— 多轨 EQ 正确性 / 单↔多轨切换 / 多轨 seek+变速同步 / 切歌 EQ 重挂

**当前质量**：单测 **89 项** + 仪器化 **6 项**，全绿（真机）。

剩余：**Phase 6 UX 打磨（F5.x）** —— 等你统一讨论 UI（含 DJ 控制台设想）；
另有两条低成本待办（README/演示脚本、分离任务前台化，见 §八）。

> 🎛️ **UI 规划（DJ 播放器界面）**：已初步成型，决策留档见 `ui_plan.artifact.md` ——
> 可折叠模块 / 转盘（波形外圈）/ 分轨滑块（2 轨单滑块）/ EQ / 分离开关（原曲↔多轨）/ 歌单 /
> 高质量分轨 / 导出（WAV）。**待实现**。

```
导入任意音频(mp3/wav/flac/aac) → 播放 → 波形拖动跳转 → 变速变调 + EQ
  → 本地 AI 分离(2 轨，可选引擎/模型) → 多轨同步播放 + 各轨音量/静音/独奏
  → 切后台继续播 + 媒体通知（N4）
```

---

## 二、必读文档（按优先级）

| 文档 | 内容 | 什么时候读 |
|---|---|---|
| **`HANDOFF.md`**（本文件） | 现状 + 约定 + 下一步 | 首先 |
| [`README.md`](README.md) | **使用说明**（构建 / 安装 / 上手 / 技术栈） | 交付 / 上手 |
| [`doc/.memory.md`](doc/.memory.md) | 过程笔记、**全部实测数据**、决策依据 | 写报告 / 需要数据时 |
| [`doc/.critical_steps.md`](doc/.critical_steps.md) | **57+ 条踩坑记录**（编号可全文引用） | 改代码出问题时 |
| [`doc/research_separation.md`](doc/research_separation.md) | 分离方案选型调研 | 想换模型时 |
| [`implementation_plan.artifact.md`](implementation_plan.artifact.md) | 滚动实施计划 | 规划下一步时 |

---

## 三、用户约定（**必须遵守**）

1. **每完成一步，先写 `doc/.memory.md` 再继续下一步** —— 用户反复强调过
2. **自动化一律走「应用内自驱动」**，禁止依赖坐标点击（原因见坑 20/32）
3. **验收证据一律走非 UI 通道**：`logcat` / `dumpsys` / `run-as ls` + `adb pull` 校验文件
4. 关键选型变更**必须在文档里留档理由**（guide §八.1 要求）

---

## 四、当前设备与测试环境

**真机（推荐用于所有验收）**
```
serial:  adb-TLF2609A004328-pQOqTm._adb-tls-connect._tcp
model:   BF00A (Aura_A1) / Android 16 (API 36) / arm64-v8a / 12GB RAM
```

**模拟器**：`emulator-5554`（API 25 / arm64 / 6GB RAM / largeHeap）
> ⚠️ **模拟器比真机快 3–5 倍**（宿主 Mac CPU 强）。报告必须用真机数据（坑 28）。

**adb 路径**（不在 PATH 里）：
```bash
ADB=/Volumes/SA500/Library/Android/sdk/platform-tools/adb
```

---

## 五、代码结构

```
app/src/main/java/pub/miku/neuralstem/
├── MainActivity.kt              入口：托管 AppShell（M2-C）+ 自动化 intent 入口
├── ui/AppShell.kt               ★底部导航栏壳（歌单/播放器/分离/EQ/设置）
├── player/                      —— 播放侧 ——
│   ├── PlayerViewModel.kt      经 MediaController 控制 + StateFlow + EQ 接口
│   ├── PlaybackService.kt      ★V2(N4)+M3: MediaSessionService；**持有 singlePlayer + 多轨**；
│   │                             session 永远认 singlePlayer（多轨主轨就是它）
│   ├── MultiTrackSession.kt    ★M3: 多轨 UI↔Service 协议（SessionCommand + 快照键）
│   ├── AudioEq.kt              ★M2-D: 单个 EQ 封装（平台 audiofx.Equalizer）
│   ├── EqController.kt         ★M2-D: 多轨 EQ 统一出口（参数同步）
│   ├── PlayerScreen.kt          播放器 UI(导入/波形/变速变调/多轨面板)
│   ├── WaveformExtractor.kt     波形包络提取(每秒 50 点);压缩格式走 Media3DecodeBridge
│   ├── Media3DecodeBridge.kt    ★仍在用!平台 MediaExtractor+MediaCodec 解码桥(勿删)
│   ├── WaveformView.kt          Compose Canvas 波形(双色/拖动/多轨预埋)
│   ├── MultiTrackController.kt  ★多轨: N 个 ExoPlayer 同步；M3 后主轨可复用外部 player
│   ├── TrackMixerPanel.kt       多轨混音 UI
│   └── SpeedPitchControls.kt    (在 PlayerScreen.kt 内) 变速变调 UI
├── settings/                    ★M2-E: 设置
│   ├── SettingsRepository.kt    默认引擎 / EQ 开关 / 首启弹窗标志（持久化）
│   ├── SettingsViewModel.kt     benchmark → 三档分组；仅实时档可作默认
│   └── SettingsScreen.kt        设备能力 + 分组列表 + EQ 开关 + 首启弹窗
└── spike/                       —— 分离侧 ——
    ├── ModelSpec.kt             ★M2-A: 模型注册表（Purpose / StemOrder）
    ├── DeviceBenchmark.kt       ★M2-B: 设备 benchmark（短/长，缓存）
    ├── SeparationScreen.kt      ★M2-C: 分离页（从 SpikeActivity 抽出，可组合）
    ├── SpikeActivity.kt         自动化入口宿主（--ez auto/benchmark）+ 分离页
    ├── FrameSource.kt           统一帧源 + Resampler + SeparationInput
    ├── SeparationRepository.kt  分离宿主：单例 + 缓存 + 取消 + clearCache + 反标签交换
    ├── StreamingSeparator.kt    分块流式分离 + 交叉淡化拼接
    ├── WavIo.kt                 probe / WavReader / WavWriter
    ├── SherpaSourceSeparation.kt sherpa-onnx JNI 封装
    └── SpikeViewModel.kt        分离页 VM（M2-F：读设置默认引擎）

app/src/main/cpp/sherpa_spike.c  JNI 胶水(支持 Spleeter/UVR 双引擎)
app/src/deprecated/              已废弃代码存档(TFLite 路线,不编译)
```

**测试**：
- 单测 **89 项**（`app:testDebugUnitTest` 全绿，实跑计数）：原有 + `MultiTrackAlignmentTest`（V1）
  + `ModelSpecTest`（M2-A）+ `SpeedRangeTest`（R6，含对数中点不变量）
- 仪器化 **6 项**（`app:connectedDebugAndroidTest` 全绿）：`ExampleInstrumentedTest`
  + `PlayerUiTest`（V5/M2-C + IA 重构，含底部导航 5 项）

---

## 六、关键实测数据（**报告请直接用真机数据**）

### 分离性能（Spleeter 2-stem fp16，30s 块，4 线程）

| 平台 | 实时性 | 峰值 PSS | 说明 |
|---|---|---|---|
| 模拟器 6G | 24.7× | 1048 MB | 仅作回归对照 |
| **真机 BF00A 12G** | **4.93–7.82×** | **744 MB** | 报告用这个 |

### ★ V3 真机多轮复测（4 分钟整曲，**实测非外推**）

| 路径 | infer（中位数，3 轮） | realtime | peakPss | 门槛 60s |
|---|---|---|---|---|
| mp3（解码） | **30.2s** | 7.94× | 739.9 MB | ✅ 余量 2× |
| wav（流式） | **30.7s** | 7.83× | 712.7 MB | ✅ 余量 2× |

- **缓存命中**：4 分钟歌端到端 **461ms**（含启动；缓存读取 ~70ms）
- ⚠️ **必须注明「前台运行」**：应用在后台时被放入 `/background` cgroup，
  同一首 4 分钟 wav 从 30.7s 变慢到 **64.7s**（~2×，实测，见 V4）

### ★ 阶段 M：UVR-MDX 真机实测（模型探索）

| 引擎（4 分钟整曲，真机前台）| infer | realtime | peakPss | 60s 门槛 |
|---|---|---|---|---|
| Spleeter fp16 | **30.4s** | **7.91×** | 707 MB | ✅ |
| UVR 9482（最小最快，28MB）| **240.4s** | **0.998×** | 1124 MB | ❌ |
| UVR Voc_FT（人声最佳，64MB）| **707.5s** | **0.339×** | 2024 MB | ❌ |

- **UVR 比 Spleeter 贵很多算力**（本机：Voc_FT 0.34× / 9482 1.0× vs Spleeter 7.9×）；
  ⚠️ **这是中端机（BF00A）的数字，不是引擎上限** —— 换旗舰机/平板会快，需重测。
- 小模型（28MB）比大模型（64MB）快 ~3×、内存减半。
- 音质上 UVR 保留高频更多（质心 4598 vs 2540 Hz）；**绝对听感待人耳判断**。
- **建议：Spleeter 作默认；UVR 作可选**（“音质优先、耗时更长”），选时给预计耗时。
- 试听包：`scratch/M/ab_preview/`（4min）与 `scratch/M/ab_variants/`（26s×6 模型）；
  或直接看 HTML 页 `ab_listening.html`。

### 分离质量（四曲实测）

| 曲目 | 类型 | vocals RMS | 结论 |
|---|---|---|---|
| Good Night | 电波/独唱 | 0.1079 | ✅ |
| 太空旅行 | 电波/独唱 | 0.1253 | ✅ |
| 新喀秋莎 | 带人声 | 0.0894 | ✅ |
| 旧喀秋莎 | 纯音乐 | 0.0015 | ✅ 正确输出（≈空 vocals） |

> **用户对分离质量的第一手评价（报告可引用）：「能对付，但是谈不上完美。」**

### 关键参数决策

| 参数 | 取值 | 依据 |
|---|---|---|
| 块长 | 默认 **30s** | 实测 5s→14.8× / 10s→23.5× / 15s→20.1× / **30s→24.7×** |
| 线程 | **4** | 2→18.9× / **4→20.1×** / 6→9.3×（腰斩） |
| 精度 | **fp16** | fp16/int8/fp32 耗时无差异，取体积最小 |
| UVR 块长 | **5s**（强制） | UVR 激活随块长线性涨，15s 会 RSS 5.8GB 被杀 |

---

## 七、踩过的最重要的坑（**改代码前请先看这些**）

完整 40 条见 [`doc/.critical_steps.md`](doc/.critical_steps.md)，以下是**最容易再次踩到**的：

| # | 坑 | 一句话 |
|---|---|---|
| **5** | JNI 签名多写 `L` | JNI 签名无编译检查，必须靠日志定位 |
| **18** | 代理对二进制做 UTF-8 消毒 | **验证压缩包必须用 `zipfile`/`tar` 实解**，`file` 命令会被撑头骗 |
| **19** | `by lazy` VM + Activity 重建 | VM 必须用 `viewModels{}`；`release()` 仅在 `isFinishing` |
| **20/32** | `am start` ≠ 真实用户路径 | 不经父 Activity 时 `setResult` 丢失；也不触发重建 |
| **23** | media3 unstable API 的 Kotlin 继承成本 | 系统框架类 < AndroidX unstable；绕不开的依赖就别绕 |
| **26** | 不必手动挂 SonicAudioProcessor | `setPlaybackParameters(speed, pitch)` 已内置 |
| **29** | `KEY_PCM_ENCODING` 可能不存在 | MediaFormat 可选键必须 `containsKey` 判空 |
| **34** | 监听器在 `load()` 前注册会扑空 | 区分「对象生命周期」与「集合内容生命周期」 |
| **35** | `player.volume` 两个写入点 | 多规则叠加的状态必须收敛到单一出口 |
| **37** | ★★**多 ExoPlayer 抢音频焦点** | **只有主轨 `handleAudioFocus=true`**，否则 N 个实例互踩 |
| **38** | 互相关延迟估计要「共享信号」 | 静音/低相干窗的 lag 是边界伪影，必须先保证可观测性再谈数值 |
| **39** | 测试 fixture 不能用反相/周期信号 | 负相关峰值不在真延迟处；用共享成分 + 伪随机信号 |
| **40** | MediaController 异步连接 | 早期请求会静默丢失，必须 pending 重放（~120ms 窗口） |

---

## 八、下一步行动建议

> **现状**：阶段 V / M / M2 / M3 / M3.5 / M4 均已完成；**R 系列（UI 重做）已完成**
> （R1/R1+/R1++/R4/R2/R3/**R6**）；**导航 IA 重构（批次 1）已完成**；
> **歌单/切歌队列化已完成**（含多轮细节修正，真机验证）。
> 数据见 [`doc/.memory.md`](doc/.memory.md) 各阶段小节。

### ★ 用户已定的执行优先级（2026 计划）

```
批次 1  导航 IA 重构（底栏 5 项 + EQ 独立页）   ✅ 已完成（真机验证）
批次 2  文档/记忆修正                          ⬅ 当前
批次 3  分离流程分析（先只分析）→ 确认 → 改进 / EQ 可用性（预设 + 人耳确认）
批次 4  F5.x（波形叠加/横屏/触感/浅色跟随系统）+ I18N 框架
批次 5  收尾项（R5 / 波形提速 / 分离前台化 / SoundTouch 最小验证）
同族小修  audit P2（清 waveform/cover）+ P3（restoreFromSession） ← 与批次2 紧接
───────────────────────────────────────────────────────────
门控     可发布版本完工 → 之后才做演示视频（用户明确）
```

### 交付物状态（guide §5.2）
- ✅ 可运行 APK / ✅ 完整源码
- ✅ **README 已有**（[`README.md`](README.md)，9KB，内容完整）
- ❌ **演示视频**（唯一缺的交付物）—— **门控：在“可发布版本”完工前完全不考虑做**

### 待确认的重点问题
- 分离页现在一页塞了 **2-stem / 4-stem / 高质量分轨+导出** 三块 → 批次 3 先做**流程分析**（不改代码）。
- EQ **无预设**（已有默认微笑曲线）→ 批次 3 补预设 + 人耳确认。
- ~~F5.3 恒深色~~ → ✅ **已完成**（批次 4：浅色 + 跟随系统）；I18N 框架已建，剩余界面待迁移。

---

## 九、快速上手命令

```bash
ADB=/Volumes/SA500/Library/Android/sdk/platform-tools/adb
SERIAL=adb-TLF2609A004328-pQOqTm._adb-tls-connect._tcp

# 构建 + 单测
./gradlew app:assembleDebug app:testDebugUnitTest
./gradlew app:connectedDebugAndroidTest    # V5 仪器化(需设备在线)

# ★V1/V3 自动化入口(API 级,不依赖坐标点击)
# 多轨播放两条 stem(配合 dumpsys media.audio_flinger 验证对齐)
$ADB -s $SERIAL shell "am start -n pub.miku.neuralstem/.MainActivity \
  --es mt_vocals <path>/vocals.wav --es mt_accomp <path>/accompaniment.wav --ez auto_play true"
# ★M3.5 新增测试 extra(可单/组合用):
#   --ez eq_test true                  载入后应用 EQ 测试曲线
#   --ei mt_seek_to <ms>               多轨下 seek
#   --ef mt_speed <f>                  多轨下变速(验证跟随轨同步)
#   --ei mt_exit_after <ms>            多轨 N ms 后退出(验证单↔多轨切换)
#   --es second_file <path> --ei second_after <ms>   播 A 后载入 B(验证切歌 EQ 重挂)
# 分离自动跑(输出 NS_RESULT {...} 结构化行,供脚本取中位数)
$ADB -s $SERIAL shell "am start -n pub.miku.neuralstem/.spike.SpikeActivity --ez auto true"
$ADB -s $SERIAL logcat -d -s SpikeVm | grep NS_RESULT
# 后台播放(N4)验证
$ADB -s $SERIAL shell input keyevent KEYCODE_HOME
$ADB -s $SERIAL shell "dumpsys activity services pub.miku.neuralstem | grep -E 'isForeground|PlaybackService'"
$ADB -s $SERIAL shell "dumpsys media_session" | grep -A2 neuralstem

# 装到真机
$ADB -s $SERIAL install -r app/build/outputs/apk/debug/app-debug.apk

# 查看应用日志(分离/播放/多轨)
$ADB -s $SERIAL logcat -s SpikeVm SepRepo PlayerVm MultiTrack StreamingSep SepInput

# ⚠️ 手机重启后需重新 push 模型到私有目录
$ADB -s $SERIAL push model_assets/sherpa-onnx-spleeter-2stems-fp16/*.onnx /sdcard/Download/
$ADB -s $SERIAL shell "run-as pub.miku.neuralstem mkdir -p files/sherpa"
$ADB -s $SERIAL shell "cat /sdcard/Download/vocals.fp16.onnx | run-as pub.miku.neuralstem sh -c 'cat > files/sherpa/vocals.fp16.onnx'"
$ADB -s $SERIAL shell "cat /sdcard/Download/accompaniment.fp16.onnx | run-as pub.miku.neuralstem sh -c 'cat > files/sherpa/accompaniment.fp16.onnx'"

# 测试素材(模拟器/真机的外部目录,loadExternal 会扫它)
#   /sdcard/Android/data/pub.miku.neuralstem/files/spike/*.{mp3,wav,flac,...}
```

**多轨验证的关键命令**（UI 看不出问题时用）：
```bash
# 看是否有 2 个 AudioTrack 都 started(排除音频焦点互踩)
PID=$($ADB -s $SERIAL shell pidof pub.miku.neuralstem | tr -d '\r')
$ADB -s $SERIAL shell dumpsys audio | grep "u/pid:10220/$PID" | grep -oE "piid:[0-9]+|state:[a-z]+"
```

---

## 十、已知限制 / 技术债

| 项 | 说明 |
|---|---|
| 分离质量 | Spleeter 2019 模型，人声渗漏/高频损失；升级路径见 §八.B |
| 波形在多轨模式 | 仍显示单轨波形（stem 波形未提取）；F5.1 会做叠加显示 |
| 分离产物路径 | 已加「清理缓存」入口（V4）；`filesDir/stems-cache/` |
| 多声道输入 | >2 声道只取前 2 声道（不做下混） |
| ~~`Media3DecodeBridge.kt` 可清理~~ | **❌ 该结论已作废**：实查 [`WaveformExtractor.kt:167`](app/src/main/java/pub/miku/neuralstem/player/WaveformExtractor.kt#L167) 仍在使用（压缩格式波形解码）。`spike/FrameSource.kt` 服务分离输入，二者职责不同，**不得删除**（V4 纠正） |
| 分离任务性能对前台敏感 | ★新发现：应用在**后台**时被放入 `/background` cgroup，4 分钟分离从 30.7s 变慢到 **64.7s**（~2×）。若要求后台也 ≤60s，分离也需前台服务 |
| 分离任务无系统通知 | N5 要求「可取消+进度回调」（已做到），但分离期间无媒体通知（播放才有，N4） |
| UVR 引擎可选但很慢 | 阶段 M 实测（中端机 BF00A）：UVR 比 Spleeter 贵 8–24× 算力；建议作**可选**而非默认，需标耗时；换强机需重测 |
| 4-stem | **已可用**（Spleeter 4-stem ONNX + 自建 DSP，见阶段 M4/S3）：四轨可播/可导出。上游 sherpa 只支持 2-stem，故 4-stem 走自建 DSP |
| 「边分离边播」未实现 | 阶段 M 评估：本机上对 Spleeter 成立（推荐 B1 自定义 DataSource，~3–5 天）；对 UVR 在部分设备不成立 |
| EQ 在 Service 侧 | M3-P3 已把 `EqController` 从 VM 搬进 `PlaybackService`；M3.5 实测单/多轨 EQ / 切歌重挂 / 单↔多轨切换均正常 |
| ~~EQ 听感未确认~~ | ✅ **已确认**（2026-09）：受控 A/B（`CMD_APPLY_EQ_EXTREME` 全±15dB）+ 人耳确认**有明显变化**；平台 EQ **确实生效**（详见 `.memory.md` §“3.2 EQ 定案”）|
| EQ 无预设 | 已经有**默认微笑曲线**（低/高 +3dB，首次开启时应用）；**可选**：再补低音增强/人声突出等预设 |
| Inst 系标签已修但仅 5 个模型 | 官方 release 有 16 个 UVR 变体，`ModelRegistry` 目前只登记了 5 个常用；新增需同步 `StemOrder` |
| 歌单页 | **已实现**（文件/文件夹导入、排序、删除、点卡头选中播放）；非占位 |
| **可选模块：NCM 格式解析** | `app/src/ncm/java`（源码级，**默认不编译/不打包**）；开启方式 `./gradlew app:assembleDebug -Pwith_ncm=true`。定位为「格式互操作性调研实现」，详见 `.memory.md` §NCM |
| TFLite 依赖 | 已废弃，代码存 `app/src/deprecated/`（保留论证） |
| 分支拓扑 | ⚠ 旧说“项目无 git”**已作废**：现有 git，`main`（状态机线，当前）+ `checkpoint-2026-09-29`（参考线）。详见本文件顶部“当前状态”。|
