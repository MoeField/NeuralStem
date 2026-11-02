# 多轨变速变调 —— 三个方案的可行性验证报告

> **日期**：2026-11-02
> **背景**：todo #1「多轨变速变调不可用」。单 AudioTrack 重构（2026-11-01）后，
> 多轨固定 1.0×，但 UI 旋钮未门控 → 用户拖动无效果，以为是 bug。
> **目标**：不是"先门控止血"，而是真正恢复多轨变速变调能力。
> **本文档目的**：把三个候选方案**分别实证**，记录每个方案为什么行/不行，
> 供最终决策与对外报告使用。

---

## 0. 结论速览

| 方案 | 做法 | 结论 | 关键证据 |
|---|---|---|---|
| **1. 输出端 Sonic** | 混音后整块送 `SonicAudioProcessor` | ✅ **唯一可行** | `TimeStretchFeasibilityTest` 6/6 通过 |
| **2. AudioTrack 原生** | `track.playbackParams = ...` | ❌ **位置计算结构性失准** | `AudioTrackParamsFeasibilityTest` 5/5 通过（证明缺陷） |
| **3. 输入端重采样** | `framePos += speed` | ❌ **变速必然连带变调** | `ResampleSpeedFeasibilityTest` 4/4 通过（证明缺陷） |

**推荐**：方案 1。它是唯一同时满足「变速」+「变调」+「位置正确」三条要求的路线。

---

## 1. 问题定义（为什么多轨特殊）

### 1.1 单轨为什么本来能用

单轨走 ExoPlayer：
```
MediaCodec 解码 → DefaultAudioSink → PlaybackParameters → AudioTrack
```
配合 `setEnableAudioOutputPlaybackParameters(true)`，由**原生 AudioTrack** 接管
speed/pitch（见 `PlaybackRenderers.kt`）。这条路对单轨成立。

### 1.2 多轨为什么这条路走不通

2026-11-01 的架构重构把多轨改成 **「单个 AudioTrack + 自研混音」**：

```
StemSource(vocals)  ┐
StemSource(drums)   ├→ StemMixer 求和 → 单个 AudioTrack.write()
StemSource(bass)    │    (± 增益/静音/独奏)
StemSource(other)   ┘
```

原因（真机实测）：旧方案"每条 stem 一个 ExoPlayer"有致命问题 ——
轨间差 800–890ms、4 条并发 AudioTrack 轮流 underrun、某轨中途暂停。
混音器是**正确的架构**，不能倒退。

但代价是：**一个 AudioTrack 承载的是混合后的信号**，无法对"每条 stem 分别变速"
—— 也不需要分别变速，用户要的是**整体**变速变调。

所以问题归结为：**如何在混音器内部加一层时间轴变换**。

---

## 2. 方案 1：输出端 Sonic ✅ 可行

### 2.1 做法

在 `StemMixer.renderLoop()` 里，把混音好的 `mix: FloatArray` 送进
`SonicAudioProcessor`，再写 AudioTrack：

```
混音 → SonicAudioProcessor.queueInput/getOutput → AudioTrack.write()
```

### 2.2 验证结果（`TimeStretchFeasibilityTest`，6/6 通过）

| # | 验证项 | 结果 |
|---|---|---|
| 1 | 纯 JVM（无 Android framework）能构造+处理 float PCM | ✅ |
| 2 | `speed=2.0` → 输出帧数 ≈ 输入的 0.5 | ✅ |
| 3 | `speed=0.5` → 输出帧数 ≈ 输入的 2.0 | ✅ |
| 4 | `speed=1,pitch=1` → `isActive()==false`（零开销旁路） | ✅ |
| 5 | 仅改 pitch 时输出时长不变（音高与速度独立） | ✅ |
| 6 | 16-bit 编码同样被支持（回退路线） | ✅ |

**不需要 Robolectric，不需要真机** —— Sonic 是纯 Java + `ByteBuffer`。

### 2.3 ★ 推翻了一个历史判断

`PlaybackRenderers.kt` 记录 SoundTouch 失败原因：

> float：媒体 3 在 BF00A 与骁龙 8 Gen3 上 `getFormatSupport(PCM_FLOAT)` 均=2（需转码）
> ⇒ 解码器不吐 float ⇒ 不可用。

**这条限制在多轨场景下不存在**：

- 那是「**设备解码器不愿吐 float**」
- 多轨的 PCM 已经在内存里是 `FloatArray`（`StemMixer` 的 `mix` 缓冲区）
- **完全不经过 MediaCodec**

`SonicAudioProcessor.configure()` 源码明确接受 float：

```java
if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
    && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
  throw new UnhandledAudioFormatException(inputAudioFormat);
}
```

⇒ float 是被支持的。历史上 SoundTouch 的墙角在**解码器侧**，不在**处理器侧**。

### 2.4 已知成本/风险

| 项 | 说明 |
|---|---|
| CPU | Sonic 是纯 Java，多轨 + 2 倍速时开销需实测（真机验证项） |
| 位置计算 | **需改为由 Sonic 输出帧数驱动**，不能再用写入计数（见 3.2） |
| 参数切换 | 频繁改参会触发 Sonic 重建 → 必须沿用现有 150ms 防抖（`PARAM_DEBOUNCE_MS`） |
| media3 API | `flush()` 已废弃，必须用 `flush(StreamMetadata.DEFAULT)`（实测会抛异常） |

---

## 3. 方案 2：AudioTrack 原生 `setPlaybackParams` ❌ 不可行

### 3.1 做法（看起来最省事）

```kotlin
track.playbackParams = track.playbackParams.setSpeed(speed).setPitch(pitch)
```

Android 原生做时间拉伸 + 音高变换，**零代码**。

### 3.2 ★ 致命缺陷：位置计算结构性失准

`StemMixer.renderLoop()` 当前按**写入量**推进播放头：

```kotlin
framePos += (written / channels)
```

一旦 AudioTrack 以 `speed` 倍速**消费**，写入速度与消费速度脱钩：

- 我们写 N 帧 → 实际播出 `N / speed` 秒
- 但 `framePos` 只前进了 N
- ⇒ 位置、进度条、seek、时长**全部失准**

### 3.3 验证结果（`AudioTrackParamsFeasibilityTest`，5/5 通过 → 证明缺陷）

| 场景 | UI 位置 | 实际源位置 | 偏差 |
|---|---|---|---|
| 1.0× | 44100 | 44100 | 0（唯一正确的情况） |
| 2.0× | 44100 | 88200 | **50%** |
| 0.5× | 44100 | 22050 | **100%**（UI 超前） |
| 时长累积 | — | — | 偏差随时长**线性放大** |

偏差严格单调递增 → 不可能靠"偶尔校准"掩盖。

### 3.4 为什么不能简单"乘以 speed"补救

理论上位置可以改成 `framePos = writtenFrames * speed`，但这要求：

1. 引入**第二个时钟**（写入计数 ≠ 消费位置）
2. 或改用 `AudioTrack.getPlaybackHeadPosition()`
3. `StemMixer.renderLoop()` 的核心循环**结构必须改**（`framePos` 不再是累加变量）

**也就是说，救它的工作量并不比方案 1 小**，而收益方案 1 全都有。

### 3.5 额外的真机风险（未验证但高度可疑）

`PlaybackRenderers.kt` 已记录：单轨上频繁改 `PlaybackParameters`
→ AudioTrack 重配 → `ERROR_CODE_TIMEOUT`（坑 48）。
多轨是否同样触发，**必须真机验证**。但既然已有更好的方案 1，没必要冒这个险。

---

## 4. 方案 3：输入端变步长重采样 ❌ 语义不达标

### 4.1 做法（实现最简单）

不改输出端，让每条 stem 的读取步长随速度变化：

```kotlin
framePos += speed   // 而不是 += 1
```

### 4.2 ★ 致命缺陷：变速必然连带变调

变步长采样 = 改变播放速率 = **改变音高**。这是物理必然
（如同黑胶 45 转 vs 33 转）。

### 4.3 验证结果（`ResampleSpeedFeasibilityTest`，4/4 通过 → 证明缺陷）

用**过零率**估算基频，量化证明：

| 场景 | 输入 | 输出频率 | 结论 |
|---|---|---|---|
| 原信号 | 440Hz | 440Hz | 基准 |
| 2.0× 变步长 | 440Hz | **880Hz** | 音高翻倍 |
| 1.5× 变步长 | 440Hz | **~660Hz** | 音高同比变化 |
| 1.0× | 440Hz | 440Hz | 无副作用 |

### 4.4 为什么不满足需求

需求 F2.1/F2.2 明确要求**速度与音高独立可调**（见 `PlayerViewModel.semitonesToRate`
与独立的 `setSpeed` / `setPitchSemitones`）。

方案 3 只能做"复古转速"效果 —— 拖速度滑块时音调跟着变，
**这正是用户抱怨的"体验降级"的具体形态**。

### 4.5 唯一可能的价值

如果将来要做"黑胶/磁带效果"这种**刻意的**变速变调联动，
方案 3 是正确选择（且极省 CPU）。但**不能用于主功能的变速变调**。

---

## 5. 横向对比

| 维度 | 方案 1 Sonic | 方案 2 AudioTrack | 方案 3 重采样 |
|---|---|---|---|
| 变速 | ✅ | ✅ | ✅ |
| 变调（独立） | ✅ | ✅ | ❌ |
| 位置计算正确 | ✅（输出帧驱动） | ❌ 需重写时钟 | ✅ |
| CPU 开销 | 中（纯 Java） | 低（原生） | **最低** |
| 需要改 `StemMixer` 结构 | 中 | 大 | 小 |
| 真机风险 | 中（需实测 CPU/音质） | 高（ERROR_CODE_TIMEOUT） | 低 |
| **满足需求** | ✅ | ⚠️ 能但代价高 | ❌ |

---

## 6. 结论与推荐

### 6.1 采用方案 1（输出端 Sonic）

理由：
1. **唯一同时满足三条要求**的方案（变速 + 变调 + 位置正确）
2. 位置计算天然正确 —— Sonic 输出的帧数就是实际播出的帧数
3. 不引入新依赖（`media3-common` 已有）
4. 默认参数下 `isActive()==false` → **1.0× 时零开销**
5. 纯 JVM 可测 → 核心逻辑能被单元测试覆盖（现有 6 个测试）

### 6.2 实施要点（给后续开发）

1. **位置时钟改造**：`framePos` 改为由 **Sonic 输出帧数**驱动，
   而非 `AudioTrack.write()` 返回值
2. **参数下发沿用防抖**：复用 `PlayerViewModel` 的 `PARAM_DEBOUNCE_MS = 150L`
   —— 避免 Sonic 被频繁重建
3. **API 注意**：`flush()` 已废弃，用 `flush(StreamMetadata.DEFAULT)`
4. **1.0× 快速路径**：`isActive()==false` 时跳过 Sonic，直接写 AudioTrack
5. **必须真机验证**：
   - CPU 占用（多轨 + 2× 的实时性）
   - 音质（对比单轨原生）
   - 拖动旋钮的稳定性（对应坑 48）

### 6.3 需要真机补验的项

本文档的验证都在 JVM 层完成，**以下必须真机确认**：

| 项 | 方法 |
|---|---|
| 多轨 + 2× 不 underrun | `dumpsys media.audio_flinger` 看 Underruns |
| CPU 占用 | `top` / 现有 `ThermalProbe` |
| 音质 | 人耳 A/B（对比单轨） |
| 拖动旋钮稳定 | 20s 内 6 次改参（复用坑 48 的压力序列） |

> ⚠️ **不要**因为本报告就断定"已经能用"。JVM 测试证明的是**算法可行性**，
> 不是**真机可用性**。真机验证是必须的下一步。

---

## 7. 证据文件

| 文件 | 内容 |
|---|---|
| `app/src/test/.../TimeStretchFeasibilityTest.kt` | 方案 1，6 项 |
| `app/src/test/.../AudioTrackParamsFeasibilityTest.kt` | 方案 2，5 项 |
| `app/src/test/.../ResampleSpeedFeasibilityTest.kt` | 方案 3，4 项 |

运行：`./gradlew :app:testDebugUnitTest --tests "*FeasibilityTest"`

---

## 8. 复用经验（避免重蹲）

| 坑 | 说明 |
|---|---|
| media3 1.11.1 `AudioProcessor.flush()` | 无参版已废弃，默认实现直接抛 `IllegalStateException`。必须 `flush(StreamMetadata.DEFAULT)` |
| 历史判断的适用范围 | "float 不可用"是**解码器侧**的限制，不能推广到**处理器侧**。多轨 PCM 不经过解码器，所以不受限 |
| 判据要量化 | 方案 3 的"变调"如果只靠人耳听，容易争论；用**过零率估算基频**可给出确定数字 |
| 位置正确性要单独验 | 方案 2 的音频输出是"对的"，但位置是错的 —— 只测"有没有声音"会漏掉这类缺陷 |
