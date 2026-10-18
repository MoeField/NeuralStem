# 设计：G-3 边分离边播（Edge Separation / Stream-While-Separating）

> 状态：**设计稿（未实现）**。请评审后再决定是否落地。
> 目标：Spleeter 2-stem 下，**分离进行中即可播放**（播到哪、分离到哪）。
> 关联：`doc/.memory.md`（阶段 M 的 B1 评估）、`doc/.todo.md` §五-5。

---

## 0. 一句话

DJ 软件（djay/Serato）**不写文件再读文件**；它们把分离出的 PCM **直接喂给音频输出**
（环形缓冲 + AudioTrack）。本设计遵循同一范式，但受限于现有 Media3 架构，
采用 **自定义 Media3 `DataSource` 从内存环形缓冲读** 的方式接入，**彻底绕开 WAV 头问题**。

---

## 1. 为什么不能「分离 → 写 WAV → 播 WAV」

现有分离链路（`StreamingSeparator` + `WavWriter`）有三个硬约束，使它**天生不能边写边播**：

| # | 约束 | 证据 |
|---|---|---|
| 1 | `WavWriter` 头部的 RIFF/data size **在 `close()` 才回填**，写入过程中恒为 0 | `WavIo.kt` `WavWriter.close()` |
| 2 | 因此分离中途的 stem WAV 对播放器是「0 长度」→ ExoPlayer 认为无内容 | 同上 |
| 3 | 分离是**整首跑完**才产出可用文件（改块长/重叠都可能产出坏音频）| `StreamingSeparator` 类注释 |

> 结论：**要边播，播放源就不能是"正在写的文件"**。可选两条路：
> - **B. 预估头 + 增量写文件**：预写一个 estimated total size，数据边算边 append，
>   播放器按"已写入长度"读。**问题**：ExoPlayer 对 WAV 的 `C.LENGTH_UNSET`/增长文件
>   支持不稳，且 seek 到未写区行为未定义。**弃**。
> - **C. 自定义 DataSource + 内存环形缓冲**（本设计）：播放器从一个"永远可读、按需阻塞等数据"
>   的数据源读 PCM/WAV 字节流。**选此**。

---

## 2. 目标与非目标

### 目标（MVP，本次设计覆盖）
1. Spleeter **2-stem** 分离开始后，**首个分段完成即可开始播放**（人声 + 伴奏同步）。
2. 播放进度**追不上**分离进度时正常播放；追上了则**播放等待**（缓冲），分离继续。
3. 分离全部完成后，无缝转为"完整 stem"（可 seek 全域）。
4. 与现有 `PlaybackService`（多轨在 Service 侧）架构**兼容**，不破坏单轨播放。

### 非目标（明确不做，避免范围失控）
- 4-stem / Demucs / UVR 的边分离边播（太慢/内存高）。
- 独立轨道音量之外的精细 crossfade 编辑。
- 分离失败/取消时"已播部分"的复杂回滚（简单回退到原曲即可）。

---

## 3. 架构

```
             ┌───────────────────────────── 分离侧（现有 repo 扩展）─────────────────────────────┐
 音频文件 →  │ FrameSource → [分段分离循环] → 每段 (vocalsFloat[], accompFloat[]) 写入 RingBuffer │
             └───────────────────────────────────────┬──────────────────────────────────────────┘
                                                     │ 追加（背压：满了等消费）
                          ┌──────────────────────────┴──────────────────────────┐
                          ▼                                                      ▼
              StemPcmRingBuffer(vocals)                             StemPcmRingBuffer(accomp)
                          │                                                      │
                 StemPcmDataSource                                     StemPcmDataSource
                          │                                                      │
              ExoPlayer(主轨, 复用 singlePlayer)                     ExoPlayer(跟随轨)
                          └──────────────► MultiTrackController（现有，AudioSink 混音）◄──────────┘
```

### 3.1 新增组件

#### `spike/StemPcmRingBuffer.kt` [NEW]
- 单写多读的 PCM 环形缓冲（float 交织，按 stem 各自一份）。
- API：
  - `fun append(samples: FloatArray)`（分离线程写；满则**阻塞**或扩容）
  - `fun read(offsetFrames: Long, dst: ByteArray, size: Int): Int`（DataSource 读；**数据不足则挂起**直到可读）
  - `fun availableFrames(): Long` / `fun isComplete: Boolean`
  - `fun close()`（分离结束/取消时唤醒所有等待者）
- 关键：**读端等待**用 `awaitility`（`Object.wait` / `Condition`），不能用忙等。
- 容量策略：初始 ~30s 窗口；超出则阻塞分离（背压），保证内存有界（对齐 N2）。

#### `player/StemPcmDataSource.kt` [NEW]
- 实现 Media3 `DataSource` + `DataSource.Factory`。
- 把**内存 PCM** 伪装成一个"WAV 流"：DataSource 内部先输出一个**合成 WAV 头**
  （size = `C.LENGTH_UNSET` 即未知长度），之后输出 PCM 字节。
  - 这样 **ExoPlayer 的 WavExtractor 可直接消费**，复用现有解码/变速链（Sonic）。
  - `open()`：重置读游标；`read()`：从 ring buffer 读，不足则阻塞；`getUri()`：伪 uri `stem://vocals`。
  - `skip(offset)`：seek 到 `offset`（支持"跳到已分离区"；未分离区则等待/或 clamp）。
- **备选（更简单但侵入更大）**：直接输出裸 PCM + 用 `RawPcm` 自定义 Extractor。
  优先 WAV 头方案（零 Extractor 改动）。

#### `spike/SegmentedSeparator.kt` [NEW]（或扩展 `SeparationRepository`）
- 复用 `StreamingSeparator` 的分块几何（chunk/overlap/交叉淡化），但**输出改写到 ring buffer**
  而非 `WavWriter`。
- 每完成一段：`buffer.append(...)` + 上报 `State.Running(percent)`。
- 首段完成 → 发 `State.FirstSegmentReady`（新增状态）→ 触发"可播"。

### 3.2 改动点

#### `player/MultiTrackController.load(...)` [MODIFY]
- 目前接受 `List<Triple<id,label,Uri>>` 并 `MediaItem.setUri(uri)`。
- 改为同时支持 **`MediaItem` 携带自定义 `DataSource.Factory`**：
  - 方案：给 ExoPlayer 每条轨 `setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))`，
    再 `setMediaItem(MediaItem.fromUri("stem://vocals"))`。
  - 最小侵入：新增一个 `loadStreaming(...)` 重载，不改现有 `load(...)`（现有路径=文件）。
- 关键：**只有主轨复用 `singlePlayer`**（现有 v2 架构），跟随轨自建。

#### `PlaybackService` [MODIFY]
- 新增命令 `CMD_LOAD_STREAM`：接收"流式 stem 描述"（每轨一个 ring buffer 句柄/或本地单例引用）。
- 因 ring buffer 在**同进程**，Service 与分离器可直接共享单例（`EdgeSeparationSession`），
  命令只传 id，不传大数据。

#### `SpikeViewModel` / 分离页 [MODIFY]
- 新增"边分离边播"开关（仅 2-stem·快速档可用）。
- 首段就绪 → 自动进多轨播放（或提示"现在可播"）。

### 3.3 状态机
- 现有 `SeparationTask` 增加 `FirstSegmentReady`（不影响既有 Idle/Preparing/Running/Done/Cancelled）。
- 与 `SessionStateMachine` 的 I3（分离一次性）保持一致：仍是一次任务，只是"可播时机"提前。

---

## 4. 关键难点与对策

| 难点 | 说明 | 对策 |
|---|---|---|
| **D1 阻塞读与 ExoPlayer 加载线程** | ExoPlayer 的 `Loader` 线程调用 `DataSource.read()`；阻塞过久会触发 load 超时 | 阻塞**带超时**（如 5s）→ 超时返回 0 字节（不报错），ExoPlayer 重试；ring buffer 持续喂 |
| **D2 时长未知** | WAV 头 size 未知 → duration 未知 → 进度/波形需另行计算 | 用"已分离时长"作 duration；全曲时长来自 `FrameSource` probe（已知） |
| **D3 seek 到未分离区** | 用户拖到未分离位置 | 简单策略：**clamp 到已分离末尾 + 提示"分离中"**（MVP）；进阶：暂停等待该段 |
| **D4 混音对齐** | 两轨必须**同一时刻**开始（现有 `MultiTrackController` 已解决 seek 对齐） | 复用现有机制；两轨 ring buffer 独立但都从 frame 0 开始 |
| **D5 内存** | ring buffer 容量 × 2 stem | 容量 30s 窗口（44.1k×2ch×4B×30×2≈42MB）；满则背压阻塞分离（不扩容）|
| **D6 Service/进程** | 分离 + 播放都在 Service 进程 | 同进程单例 `EdgeSeparationSession` 共享 ring buffer，零跨进程传输 |
| **D7 前台服务** | 分离已前台（G-4）；播放也已前台 | 两者并存，互不冲突 |

---

## 5. 验证计划

### 自动化
- 单测：`StemPcmRingBuffer`（多读、阻塞、背压、close 唤醒）；`StemPcmDataSource`（WAV 头合成、read/skip、EOF 语义）。
- 回归：现有 128 单测 + 7 仪器化必须全绿（不破坏现有文件路径）。

### 真机（BF00A，非 UI 通道）
1. 启动边分离边播 → `dumpsys media.audio_flinger` 应有 **2 条 active AudioTrack**（两 stem）。
2. 观察 **首段可播延迟**（分离开始 → audio_flinger 出现播放轨的时间）。
3. 播放进度 vs 分离进度：播放不得超过"已分离点"（通过日志/时长推断）。
4. 分离完成 → 仍持续播放、可 seek 全曲。
5. 取消分离 → 播放回退原曲、无崩溃、ring buffer 释放。

---

## 6. 工作量与风险

| 项 | 估时 | 风险 |
|---|---|---|
| RingBuffer + 单测 | 0.5 天 | 低 |
| StemPcmDataSource + WAV 头合成 | 1 天 | **中高**（Media3 DataSource/Extractor 细节）|
| SegmentedSeparator（改造输出） | 1 天 | 中（复用 StreamingSeparator 几何）|
| Service/Controller 接入流式 | 1 天 | **高**（多轨 + 流式 + 主轨复用交织）|
| UI/状态机 + seek 降级 | 0.5 天 | 中 |
| 真机联调 | 1 天+ | **高**（无音频反馈通道，靠 dumpsys 推断）|

**合计 ≈ 5 天**，与 `batch3_separation_analysis` 的 B1 评估一致。
**风险最高**：Media3 流式 DataSource 的加载/重试行为 + 多轨主轨复用。

### 回退策略
- 实现为**并行的可选路径**（开关控制）；默认仍走"分离完再播"。
- 若 Media3 流式不成立 → 降级为"首段完成后即自动播放（B 方案：分段写文件 + 预估头）"，
  或直接砍掉，README 标注 G-3 未做。**不阻塞 alpha**。

---

## 7. 开放问题（需你决定）
1. **seek 到未分离区**：MVP 采用"clamp 到已分离末尾 + 提示"，可接受吗？（进阶=等待）
2. **首段就绪后**：自动切多轨播放，还是给一个"▶ 现在可播"按钮（更可控）？
3. **默认开关**：边分离边播默认开启，还是默认关（用户手动开）？
