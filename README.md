# NeuralStem

> **版本：`0.1.0-alpha`** —— alpha 首发。功能已闭环（导入/波形/变速变调/本地分离/多轨/EQ/后台），
> 已知限制见文末。

**本地 AI 音轨分离播放器** —— 在 Android 设备上完全离线地把一首歌拆成人声 / 伴奏两条音轨，
并实时调节各轨音量、播放速度与音高。

面向 **音乐学习者与播客听众**：用本地算力免费实现 djay Neural Mix 式的核心体验
（分离 + 分轨混音 + 变速变调），不追求专业 DJ 功能。

---

## 功能一览

| 能力 | 说明 |
|---|---|
| **导入播放** | 支持 mp3 / wav / flac / aac / m4a / ogg（SAF 文件选择器） |
| **波形交互** | 自绘波形进度条，拖动即**实时跳转**；已播 / 未播双色区分 |
| **变速** | 0.5× – 2.0×，**变速不变调**，实时生效不中断 |
| **变调** | ±12 半音，**变调不变速**，实时生效不中断 |
| **本地 AI 分离** | 完全离线推理（sherpa-onnx / ONNX Runtime），输出人声 + 伴奏两条轨 |
| **多轨混音** | 2 轨 / 4 轨 stem 同步播放，各轨独立音量 / 静音 / 独奏 |
| **多轨波形叠加** | 多轨时转盘外环**按轨多色叠加**显示各轨波形（F5.1）|
| **均衡器（EQ）** | 5 段均衡（平台 `audiofx.Equalizer`），单轨 / 多轨均可用 |
| **后台播放** | 切后台 / 锁屏继续播，带系统媒体通知（`MediaSessionService`） |
| **后台分离** | 分离任务跑在 `dataSync` 前台服务（+进度通知）→ 后台不降频 |
| **边分离边播（G-3）** | 可选（默认关）：2 轨·快速档从**当前播放位置**起分离，首段就绪自动切 |
| **模型管理** | 多模型 + 分离档位（2轨 3 档 / 4轨 2 档）；首启**设备 benchmark**，按本机实测速度分档 |

**核心流程**：

```
导入音频 → 播放（波形拖动 / 变速变调 / EQ）
        → 本地 AI 分离（2 轨）→ 多轨同步播放 + 各轨音量 / 静音 / 独奏
```

---

## 快速上手

### 1. 环境要求

- Android Studio（含 Android SDK；编译需 **API 37**）
- **NDK `30.0.16248370`** + **CMake `4.1.2`**（构建 sherpa-onnx JNI 胶水层）
- 设备：真机或模拟器，支持 **arm64-v8a / x86_64**

> 换机器时需确认本机已安装上述 NDK / CMake 版本（AGP 默认首选版本可能未装）。

### 2. 放入分离模型（必需）

分离功能需要 ONNX 模型文件。本项目使用 **Spleeter 2-stem fp16**
（[sherpa-onnx 官方 release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/source-separation-models)，
`vocals.fp16.onnx` + `accompaniment.fp16.onnx`，各 ~19.7 MB，已随仓库放在
`model_assets/sherpa-onnx-spleeter-2stems-fp16/`）。

模型需从**应用私有目录**读取（外部目录在 Android 11+ 下 app 可能读不到）：

```bash
ADB=/path/to/Android/sdk/platform-tools/adb
PKG=pub.miku.neuralstem

adb shell "run-as $PKG mkdir -p files/sherpa"
adb push model_assets/sherpa-onnx-spleeter-2stems-fp16/vocals.fp16.onnx /sdcard/Download/
adb push model_assets/sherpa-onnx-spleeter-2stems-fp16/accompaniment.fp16.onnx /sdcard/Download/
adb shell "cat /sdcard/Download/vocals.fp16.onnx        | run-as $PKG sh -c 'cat > files/sherpa/vocals.fp16.onnx'"
adb shell "cat /sdcard/Download/accompaniment.fp16.onnx | run-as $PKG sh -c 'cat > files/sherpa/accompaniment.fp16.onnx'"
```

### 3. 构建与安装

```bash
./gradlew app:assembleDebug                    # 构建 debug APK
./gradlew app:installDebug                     # 直接装到已连接设备
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 4. 运行

1. 打开应用（主页 = **播放器**）
2. 点「**导入音频**」选一首歌 → 自动播放
3. 拖动**波形**跳转；用滑块调**速度 / 音高**（EQ 在「混音」页）
4. 点底部**「分离」** →「**开始分离**」（首次会提示检测设备性能；未检测则按钮禁用）
5. 分离完成 → 自动切多轨 / 或回播放页拨「分离开关」
6. 点底部**「混音」** → 调各轨音量 / 静音 / 独奏 + EQ

---

## 界面结构

导航壳（**底部导航栏 5 项**；竖屏有顶栏，横屏隐藏顶栏）：

| 页面 | 内容 |
|---|---|
| **歌单** | 文件/文件夹导入 / 排序 / 删除 / 点曲播放（并作为播放队列）|
| **播放器** | 黑胶转盘 + 波形拖动 / 导入 / 播放暂停 / 变速变调 / 分离开关 |
| **分离** | 当前曲分离（档位：2 轨快速/均衡/质量 · 4 轨快速/质量）→ 去多轨播放 + 导出 WAV |
| **混音** | 分轨混音（各轨音量/静音/独奏）+ EQ 面板 |
| **设置** | 设备能力 + 模型三档分组（⚡ 实时 / ⏱ 较慢 / 🐢 很慢）+ 默认引擎 + 转盘时长 / 变速范围 |

---

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose（Material 3） |
| 音频播放 | **Media3 / ExoPlayer**（多实例架构，每轨一个实例） |
| 变速变调 | ExoPlayer 内置 **Sonic**（`setPlaybackParameters`，无需手动挂 AudioProcessor） |
| 波形 | 自绘 Compose Canvas（每秒 50 个包络点，与采样率无关） |
| 本地分离 | **sherpa-onnx**（ONNX Runtime）· Spleeter 2-stem ONNX |
| 均衡器 | 平台 `android.media.audiofx.Equalizer` |
| 后台播放 | `MediaSessionService` + 系统媒体通知 |
| 最低 / 目标 API | minSdk **23** · targetSdk **37** |

**架构要点**：播放器与多轨混音**都放在 `PlaybackService`**（前台服务）里，
UI 通过 `MediaController` 控制 —— 因此单轨与多轨的后台行为完全一致。
详见 `doc/.memory.md` §阶段 M3。

---

## 项目结构

```
app/src/main/java/pub/miku/neuralstem/
├── MainActivity.kt            入口（托管 AppShell）
├── ui/AppShell.kt             ★ 底部导航栏 5 项（歌单/播放器/分离/混音/设置）
├── ui/theme/                  Material 3 主题
├── player/                    播放侧
│   ├── PlaybackService.kt     ★ MediaSessionService：持单轨 player + 多轨控制器 + EQ
│   ├── SessionState.kt        ★ 状态机（单一真源，I1–I4 不变量）
│   ├── PlayerViewModel.kt     经 MediaController 控制 + UI 状态
│   ├── PlayerScreen.kt        播放器 UI（含黑胶转盘 DeckView）
│   ├── DeckView.kt / CoverExtractor.kt   黑胶转盘 + 封面提取
│   ├── MixScreen.kt           混音页（分轨混音 + EQ）
│   ├── WaveformExtractor.kt   波形包络提取（流式/节流 emit）
│   ├── WaveformCache.kt       波形文件缓存（全曲解码 ~20s→命中~秒出）
│   ├── WaveformView.kt        Compose 波形组件（拖动 seek / 多轨预埋）
│   ├── MultiTrackController.kt  多 ExoPlayer 同步 + 音量/静音/独奏
│   ├── MultiTrackSession.kt   UI ↔ Service 的自定义命令协议
│   ├── AudioEq.kt / EqController.kt  均衡器封装与多轨统一出口
│   ├── TrackMixerPanel.kt     多轨混音面板
│   └── Media3DecodeBridge.kt  平台 MediaExtractor+MediaCodec 解码桥（仍在用，勿删）
├── playlist/                  歌单（导入/排序/持久化 + 队列语义）
├── settings/                  设置页（模型分组 / 默认引擎 / 转盘时长 / 变速范围）
├── export/                    导出 WAV（StemExporter）
├── devtools/                  调试命令总线 + 测试面板（仅 debug）
└── spike/                     分离侧
    ├── SeparationRepository.kt  分离宿主（进程级单例 + 缓存 + 取消）
    ├── StreamingSeparator.kt    分块流式分离 + 交叉淡化拼接（2-stem）
    ├── Spleeter4Separator.kt / Spleeter4Dsp.kt   4-stem 自建 DSP
    ├── DemucsSeparator.kt / DemucsStft.kt         htdemucs 4 源
    ├── TierRegistry.kt / BenchmarkTable.kt        档位注册表 + CPU↔模型对照表
    ├── SeparationScreen.kt      分离页 UI
    ├── FrameSource.kt           统一帧源（wav 流式 / 压缩格式解码 + 重采样）
    ├── ModelSpec.kt             模型注册表（用途 / stem 顺序）
    ├── DeviceBenchmark.kt       设备能力探测
    ├── WavIo.kt                 WAV 流式读写（probe / Reader / Writer）
    ├── SherpaSourceSeparation.kt  sherpa-onnx JNI 封装
    └── SpikeActivity.kt / SpikeViewModel.kt  分离页宿主与 VM

app/src/main/cpp/sherpa_spike.c   JNI 胶水层
app/src/deprecated/               已废弃代码存档（TFLite 路线，不编译）
app/src/ncm/                      可选模块：NCM 格式解析（默认不编译，见下）
```

---

## 构建选项

| 选项 | 说明 |
|---|---|
| `./gradlew app:assembleDebug` | 标准 debug 构建 |
| `-Pwith_ncm=true` | 启用**可选的 NCM 格式解析模块**（`app/src/ncm/`，默认**不编译、不打包**）。定位为格式互操作性调研实现，不接入播放。 |

---

## 测试

```bash
./gradlew app:testDebugUnitTest          # 单元测试（128 项）
./gradlew app:connectedDebugAndroidTest  # 仪器化测试（7 项，需设备在线）
```

**单元测试覆盖**：WAV 流式读写 / 分块流式分离拼接 / 重采样 / 波形包络 /
变速变调换算 / 多轨对齐 / 模型注册表 / EQ 多轨同步。

---

## 性能（真机实测）

设备：BF00A（Android 16 / 12 GB / arm64）。

| 项 | 结果 |
|---|---|
| 4 分钟歌分离（Spleeter fp16） | **30.2 s**（mp3）/ **30.7 s**（wav），≈ 7.9× 实时 |
| 峰值内存 | ~710–740 MB |
| 缓存命中 | < 100 ms |
| 目标门槛 | ≤ 60 s ✅（余量约 2×） |

> ⚠️ 以上为**前台运行**数据。应用在后台会被系统放入低优先级 cgroup，分离耗时可增至约 2×。

**分离质量说明**：使用 Spleeter（2019 模型），人声/伴奏可分离，但存在人声渗漏与高频损失；
对「学歌 / 扒谱」场景足够，非出版级。

---

## 文档

| 文档 | 内容 |
|---|---|
| [`doc/guide.md`](doc/guide.md) | 需求文档（功能 / 非功能需求、验收标准） |
| [`doc/.memory.md`](doc/.memory.md) | 过程笔记与**全部实测数据**、决策依据 |
| [`doc/.critical_steps.md`](doc/.critical_steps.md) | **64 条踩坑记录**（改代码前建议先看） |
| [`doc/research_separation.md`](doc/research_separation.md) | 分离方案选型调研 |
| [`doc/demo_script.md`](doc/demo_script.md) | 演示视频分镜脚本 |
| [`HANDOFF.md`](HANDOFF.md) | 项目现状 / 约定 / 下一步 |

---

## 已知限制（alpha）

- **分离质量**：Spleeter（2019）模型有渗漏/高频损失，非出版级；4 轨/Demucs/UVR 更慢。
- **边分离边播（G-3）**：✅ **已实现**（默认**关**，设置页可开）。开启后选「2 轨·快速」档时，
  从**当前播放位置**起分离，首段就绪自动切。
  - 仅 **2 轨·快速** 档支持；4 轨 / Demucs / UVR 仍为“分离完再播”。
  - 已知体验欠缺：点击后需等 **~1–3 秒**才切到分离结果（**暂无视觉引导**，正式版补）。
  - seek 到“尚未分离到”的位置会被限制在已分离区（MVP）。
- **多声道输入**：> 2 声道只取前 2 声道（不做下混）。
- **MP3 特定位置 seek 卡顿**：个别机型软解器缺陷（有自动恢复兑底，App 层不可根治）。
- **重装 app 后模型丢失**：需重新 push `files/sherpa/*.onnx`（见“快速上手 §2”）。

> ✅ 已实现（早期列为限制，现均已解决）：各轨波形叠加（F5.1）、后台分离降频（G-4 前台化）、
> 横屏 / 深色跟随系统 / 触感 / 歌单（均已完成）。
