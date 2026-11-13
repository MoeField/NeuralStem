# 路径清单与逻辑自查（PATH_AUDIT）

> 状态：**审计稿，只记录现状与问题，暂不改代码**  
> 基线：`main` / `v0.2.14`  
> 目的：把播放、分离、缓存、UI 的路径全部摊开，再按路径自查逻辑 bug；
> 详细维修计划在下一步单独出。

---

## 0. 审计范围与方法

覆盖以下入口与边界：

1. 启动 / Activity 重建 / 进程恢复
2. SAF / content:// / file:// / NCM 源导入
3. 歌单 / 队列 / 顺序播放 / 自动下一首
4. 2-stem、4-stem、Demucs、edge 四种分离启动方式
5. 缓存命中、续算、补前段、取消、失败
6. 分离结果 attach / detach / 多轨播放
7. 导出 / 清缓存 / 模型库
8. 播放结束、切歌、Service 丢失
9. 发布 / CI / 许可边界（只列入口，不展开）

方法：

- 以 `SpikeViewModel`、`SeparationDomain`、`SeparationController`、`SeparationRepository`、
  `EdgeSeparationSession`、`PlayerViewModel`、`AppShell`、`SeparationScreen` 为线索，
  从**用户操作**跟到**状态/存储/播放器**，再从回调反查状态归属。
- 只记录能在代码中定位的路径；用户报告但未复现的现象标记为“待复现”。
- 不把 R8、UI 配色、模型数值精度纳入本次逻辑审计。

---

## 1. 路径总览

```text
用户/SAF/歌单
   │
   ├─ 选曲 ──> PlayerViewModel.doLoad ──> PlaybackService ──> 单轨/多轨播放
   │              │
   │              └─ 播放结束/下一首 ──> autoAdvanceOnEnded ──> TrackChanged
   │
   └─ 分离页 ──> SeparationScreen
                    │
                    ├─ CurrentTrackBridge.toFile(uri)   ← 输入路径（临时文件）
                    ├─ SpikeViewModel.start*            ← 入口
                    ├─ SeparationDomain.start/controller ← 领域状态
                    ├─ SeparationRepository.*            ← 引擎/缓存
                    │      ├─ full 2/4-stem
                    │      └─ edge（全局 EdgeSeparationSession）
                    └─ Domain Effect → PlayerBridge → PlayerViewModel.loadMultiTrack
```

### 1.1 关键所有者

| 数据/行为 | 当前所有者 | 备注 |
|---|---|---|
| 播放状态/当前曲 | `PlayerViewModel` + `PlaybackService` | `SessionStateMachine` |
| 分离任务状态 | `SeparationDomain` + `SeparationController` | v0.2.14 收口 |
| 边分离引擎/闸门 | `EdgeSeparationSession` | 进程单例，仍全局 |
| 分离引擎/缓存/磁盘 | `SeparationRepository` | 进程单例 |
| 分离页 UI 任务态 | `SpikeViewModel` 从 Domain 映射 | 仍保留 `SeparationTask` 适配 |
| 分离开关/多轨 | `PlayerViewModel.isMultiTrack` + `SeparationEdgeUi` | 不是 Domain attachment 直接驱动 |

---

## 2. 启动 / 恢复路径

| 路径 | 入口 | 数据流 | 风险点 |
|---|---|---|---|
| 冷启动 | `MainActivity` → `AppShell` | `PlayerViewModel` 连接 Service，读 VM/Service extras | Service 尚未连接时的 `pendingLoad` |
| Activity 重建 | 旋转/返回 | `AppShell` 重建；`SpikeViewModel` 从 Domain snapshot 恢复；`PlayerViewModel` 从 Service extras 恢复 | `separationTask` 的一次性消费、Domain 订阅时序 |
| 进程死亡后恢复 | 重新启动 | Domain 单例重建；active job 不恢复；完成结果靠 cache fast path | `SeparationDomain.onProcessRestored` **目前没有调用点** |
| 播放中断 | 来电/焦点丢失 | Service/ExoPlayer 焦点；多轨由 Service 持有 | 焦点的真实通话场景未测 |

**自查**：

- `SeparationDomain.onProcessRestored()` 是死代码；进程恢复实际靠 cache/VM 对账，
  与文档 D3 不完全一致，但不直接造成错误结果。

---

## 3. 媒体源与导入路径

### 3.1 源类型

| 源 | 进入 | 复制/解码 | 备注 |
|---|---|---|---|
| `file://` | SAF 之外的外部文件 | `CurrentTrackBridge.toFile` 直接复用 | 文件可能被移动/删除 |
| `content://` | SAF/网易云 | `CurrentTrackBridge.toFile` 复制到 `cacheDir/separate_input.<ext>` | **固定名**，A/B 同扩展会覆盖 |
| `.ncm` | NCM 可选模块 | NCM 解密后内存/临时数据源 | 默认构建不编译 |
| AVS3 | 可选 NCM | 解码到 PCM 缓存 | 默认 release 不启用 |

### 3.2 `CurrentTrackBridge`

当前实现：

```kotlin
private const val CACHE_NAME = "separate_input"
val local = File(context.cacheDir, "$CACHE_NAME.$ext")
```

- 固定文件名；`who_am_i.flac` 与 `bullet.flac` 都是 `.flac`，会写同一个文件。
- 复制发生在点击分离时的协程中；如果 A 的分离还在读该文件、或 A/B 快速切换，
  源内容可能与当前曲身份不一致。
- 对于压缩源，`SeparationInput.open` 随后还会把 `separate_input.<ext>` 解码成
  `sep-src-<timestamp>-<hash>.wav`，即**两层临时文件**。

---

## 4. 播放 / 队列 / 自动下一首路径

| 路径 | 入口 | 关键点 |
|---|---|---|
| 播放歌单曲 | `PlaylistScreen` → `PlayerViewModel.loadAndPlay` | 队列、进度记忆、SAF grant |
| 下一首/上一首 | MediaSession/Service/playlist | 队列同步、`currentListId` |
| 自然结束 | `onPlaybackStateChanged(STATE_ENDED)` / `mixEnded` | 单轨与多轨结束时刻不同 |
| 自动下一首 | `autoAdvanceOnEnded` → `loadAndPlay` | 会触发 `TrackChanged` |
| 单曲循环 | `REPEAT_MODE_ONE` / mixer restart | 多轨循环单独处理 |
| 手切曲 | 用户点歌 | `doLoad` 清理 `lastStems` / 多轨 |

**自查**：

- 播放结束转发 Domain 的调用已加到 `PlayerViewModel`（`STATE_ENDED` / `mixEnded`），
  但 `Seamless` 的下一首事件与 Domain `TrackChanged` 的先后没有集成测试。
- 自动下一首时，`SeparationScreen` 的 `currentUri` 参数可能比 `PlayerState.currentUri`
  慢一个重组帧；用户在切换瞬间点「极快」可能带着 A 的 uri 启动（见 BUG-01/04）。

---

## 5. 分离启动路径

### 5.1 四个入口

| 入口 | 后端 | 关键 API |
|---|---|---|
| 2-stem 普通 | Sherpa Spleeter/UVR | `SpikeViewModel.startOption` → Domain `SHERPA_2STEM` → `repo.start` |
| 4-stem 普通 | Spleeter4 | `start4Stem` → Domain `SPLEETER4` → `repo.start4` |
| Demucs 4-stem | Demucs | `startOption` → Domain `DEMUCS` → `repo.startDemucs` |
| edge 2-stem | Spleeter | `startEdgeOption` → Domain `EDGE` → `repo.startEdge` |

### 5.2 启动时序

```text
UI 点击
  → SeparationScreen.start*（捕获 currentUri）
  → CurrentTrackBridge.toFile(uri)        // 复制/复用
  → SpikeViewModel.start*(ownerUri)
  → Domain.start(StartRequest)
  → Controller.StartDecode effect
  → SeparationRepository.start*
  → repo.state / EdgeSession.state 回调
  → Domain SourceReady/Coverage/JobFinished
  → SpikeViewModel 映射 SeparationTask
  → Domain Effect LoadStems
  → PlayerBridge.loadMultiTrack
```

风险点：

1. **ui 捕获 uri → 物理复制 → Domain 启动**之间没有“当前曲仍然一致”校验。
2. `SeparationDomain.startEngine` 在 `repo.start*` 前就写入 `activeJobId`；旧 repo 状态
   可能在此期间被错误归属。
3. `CurrentTrackBridge` 固定临时名，A/B 同扩展会互相覆盖。
4. `SpikeViewModel.start4Stem` 的 ownerUri 在 M3 已修，但 `startSeparation` 等自动入口
   仍可能用空 TrackKey。

### 5.3 缓存路径

| 类型 | 目录 |
|---|---|
| 2-stem full | `stems-cache/<contentHash>-<engineTag>-full/` |
| 4-stem | `stems-cache/<contentHash>-spleeter4/` |
| Demucs | `stems-cache/<contentHash>-demucs/` |
| edge | `stems-cache/<contentHash>-spleeter/` |

- 只用 contentHash+engine，没有歌曲名/模型名目录；导出时才用歌曲名。
- 同一曲不同模型目录不同；同一模型不同曲 hash 不同，**缓存本身不会跨曲**。
- 真正可能串的是**临时输入文件**和**Domain 的 job 归属**，不是缓存 hash。

---

## 6. 边分离（edge）路径

### 6.1 阶段

```text
Preparing（解码/加载模型）
  → FirstSegmentReady（可切）
  → Running（水位线推进）
  → Done（全曲完成）
  → 补齐前段（backfill，独立进度）
```

`EdgeSeparationSession` 是**全局单例**，包含：

- `state` / `gate`
- `startFrame` / `totalFrames` / `writtenUpToFrame`
- `vocalsFile` / `accompFile`
- `frontCovered` / `pendingBackfillRange`
- `inBackfill` / `backfillPercent`

### 6.2 Domain 映射

- `tickEdge()` 每 200ms 读取全局 `edge`，并发送给当前 `activeJobId`。
- `onRepoState(EdgeDone)` 也读取全局 `edge` 的 total/start 来构造 coverage。
- **没有校验 edge 会话属于当前 job/当前曲**。

这是 A/B 串结果的最强嫌疑路径：B 启动后旧 A 的 `EdgeSession` 还活着 200ms 时，
Domain 可能把 A 的 `writtenUpToFrame`/`vocalsFile` 发送为 B 的 coverage/files。

---

## 7. 完成、attach、多轨路径

| 路径 | 入口 | 当前行为 |
|---|---|---|
| edge 自动切轨 | Domain `CoverageUpdated(gateReady)` → `LoadStems` → PlayerBridge | `SpikeViewModel` 不再负责 auto switch |
| full 2-stem 完成 | Domain `JobFinished` → `SpikeViewModel` Done2 → `SeparationScreen` 消费 → AppShell `onPlayStems` | 用户点开关/自动回调 |
| 4-stem 完成 | 同上 Done4 | 导出按钮依赖 `lastExportable` |
| 手动 off | `PlayerViewModel.toggleSeparation(false)` | Domain attachment 不更新 |
| 手动 on | `toggleSeparation(true)` 用 `lastStems` | Domain attachment 不更新 |
| 换曲 | `AppShell` → Domain `TrackChanged`；Player `doLoad` 清 `lastStems` | Domain detach 走 Effect |
| 播放结束 | `PlayerViewModel` → Domain `PlaybackEnded` | edge cancel/detach；full 继续 |

**自查**：

- 手动开关仍绕过 Domain；Domain 的 `attachment` 可能与播放器实际多轨不一致。
- `SpikeViewModel.applyDomainSnapshot` 对 Finished 任务**没有按 currentTrackKey 过滤**：
  - FULL 后台继续完成后（D1），当前已是 B，映射仍可能把 A 的 Done2/Done4 推给 UI。
  - `SeparationScreen` 消费后可能触发 A 的播放/导出提示，取决于 AppShell owner 校验时序。
- `lastMappedDoneJobId` 只按 jobId 去重，不按曲。

---

## 8. 导出路径

`StemExporter.export(context, songName, stemFiles, original)`：

- 目录：`Music/separate/<safeSong>/`
- 文件：`vocals.wav` / `drums.wav` / `bass.wav` / `other.wav` / `accompaniment.wav` / `original.<ext>`
- **没有模型名维度**；Spleeter 2-stem 与 4-stem、Demucs 会写到同一目录并覆盖同名文件。
- 2-stem 当前 README 说不支持导出，但 `SeparationScreen` 的 export 逻辑/`lastExportable`
  可能允许 2 轨；需要以 UI 实际门控为准（待核实）。
- 导出 owner 只校验 `ownerUri`；模型/任务没有绑定。

---

## 9. 取消 / 失败 / 服务路径

| 事件 | Domain 行为 | 风险 |
|---|---|---|
| 用户取消 | `Cancel` → `CancelJob` → repo.cancel；Cancelled 后 detach edge | full 取消是否 detach 未定义 |
| 解码失败 | `JobFailed`；edge 失败后 detach | 旧 job 的 Failed 需 jobId 过滤 |
| 取消超时 | `Cancelling.workerAlive=true`；不启动新 worker | 依赖超时事件实际被谁发出（M3 尚未接 watchdog） |
| 播放 Service 丢失 | Domain `ServiceLost(PLAYBACK)`；edge cancel；full 继续 | 当前 `onPlaybackServiceLost` 没有实际调用点 |
| 分离 Service 丢失 | `JobFailed` | 也需外部事件实际调用 |
| 进程恢复 | `onProcessRestored` 死代码 | 实际靠 cache/VM 对账 |

---

## 10. 自查发现的逻辑 bug

> 级别：P0 = 会播/显错曲结果；P1 = 状态不一致/可复现错行为；P2 = 命名/维护/恢复。  
> 本次只审计，不修。

### BUG-01 [P0] A/B 快速切歌时可能把 A 的分离结果给 B

**路径**：A 顺序播完 → B 自动下一首 → B 分离页点「极快」。

**可能的独立成因（可叠加）**：

1. `CurrentTrackBridge` 固定 `separate_input.<ext>`：A/B 同扩展互相覆盖。
2. `SeparationScreen.startSherpaOption` 捕获 `currentUri`/`uri` 后做复制；切歌窗口内可能仍是 A。
3. `SeparationDomain.startEngine` 在 repo 启动前设置 `activeJobId`；旧 A 的 repo 状态可被归属到 B。
4. `SeparationDomain.tickEdge` / `onRepoState(EdgeDone)` 读全局 `EdgeSeparationSession`，不校验 source。
5. `SpikeViewModel.applyDomainSnapshot` 不按 currentTrackKey 过滤 Finished。
6. `JobFinished`/`LoadStems` 无 source token，UI owner 校验只防一部分。

**证据**：代码位置见 §5/§6/§7；简单 A→B（A 未分离）测试时 B 命中的是 B 的 cache，
说明不是每次必现，而是**任务重叠/复制窗口**下的竞态。

**建议**：unique source file + source/job token + tickEdge source 校验 + start current-track 校验。

**修复进度（2026-11-13，`fix/path-audit`）**：

- 已实现唯一临时源：`separate_input_<uriHash>.<ext>`
- repo 终态新增 `sourcePath`；Domain 只接受与 activeSourcePath 匹配的终态
- start 校验 `request.trackKey == currentTrackKey`
- EdgeSession source 不匹配时 tickEdge/publishEdgeUi 不发事件
- SpikeViewModel Finished 按 currentTrackKey 过滤
- 真机 A（who，已 edge 多轨）→ 自动下一首 B（bullet）→ B 极快：
  `TrackBridge` 分别生成 `separate_input_c44e105d.flac` / `separate_input_7659cab2.flac`，
  B 命中 `25641fd5d493ca5e-spleeter`，A 的 `3d42e864dce0c19a` 未被复用。
- JVM 437/0。

### BUG-02 [P1] `tickEdge`/`publishEdgeUi` 可能把旧 edge 会话当作当前 job

- `EdgeSeparationSession` 是全局单例；`tickEdge` 只看 `activeJobId` 和 `current.kind==EDGE`，
  不看 `edge.sourceUri`/generation/startFrame 是否等于当前 job。
- 旧 A 的 EdgeSession 在 B 启动后未立即 abort 时，B 的 coverage/失败/文件可能来自 A。
- `publishEdgeUi` 同理，UI 进度可能是上一首的。

### BUG-03 [P1] repo 终态没有 job/source token

- `SeparationRepository.State.Done/Done4/EdgeDone` 不含 sourcePath/jobId；
- Domain `onRepoState` 用 `activeJobId` 归属，迟到终态会把 A 结果写成 B 的 `JobFinished`。

### BUG-04 [P1] 启动时没有“当前曲仍然一致”校验

- `SeparationDomain.start()` 不比较 request.trackKey 与 snapshot.currentTrackKey；
- `SeparationScreen` 用点击时捕获的 uri；
- 自动下一首与点击时间的竞态可能启动上一首。

### BUG-05 [P1] Finished 任务未按当前曲过滤

- `SpikeViewModel.applyDomainSnapshot` 的 `Lifecycle.Finished` 分支不检查 `job.trackKey == snapshot.currentTrackKey`；
- FULL D1 后台完成后，当前是 B 时仍可能把 A 的 Done 推给 `SeparationTask`/export。

### BUG-06 [P1] 手动开关绕过 Domain（已修）

- **已修**：AppShell `domainToggleSeparation` 接 Domain attach/detach，PlayerScreen/
  SeparationScreen 回调改走它；Domain attachment/suppression 与播放器一致。
- 真机：edge 自动多轨 → off → on 正常。

### BUG-07 [P2] 导出缺少模型维度

- 目录只有 `Music/separate/<song>/`；
- Spleeter 2-stem、Spleeter 4-stem、Demucs 的同名文件会覆盖；
- 与“每次分离应按歌+模型区分”的旧需求不一致。

### BUG-08 [P2] 进程恢复/服务丢失事件未完全接线（部分修）

- **已修**：PlaybackService/SeparationService `onDestroy` 通过 Domain.peek 上报 ServiceLost；
- `onProcessRestored` 仍无调用点；进程恢复仍靠 cache/VM 对账。

### BUG-09 [P2] 两层临时文件与清理

- content:// 先复制 `separate_input.<ext>`，压缩源再解码 `sep-src-*.wav`；
- 固定名、无按 job 清理；旧文件堆积且可能被覆盖。
- 对压缩源第一层可移除（直接解码 content URI/fd）；WAV 需一次本地副本或可 seek fd。

### BUG-10 [P2] UI 映射仍保留一次性消费/适配层

- `SpikeViewModel` 仍把 Domain snapshot 映射成 `SeparationTask`；
- `lastExportable`/`_lastEdgeDone`/ownerUri 与 Domain 状态并行，存在双重状态回退风险。
- 不是立即功能 bug，但后续迁移容易再出状态错配。

---

## 11. 测试覆盖现状

- JVM：433/0。
  - `SeparationReducerTest`：迁移/交错/终态/超时/缓存/导出门控等。
  - `SeparationControllerTest`：事件串行/缺省 effect/旧 job。
- 真机已过：
  - edge fast path 自动切多轨；
  - 4-stem cache/启动；
  - 开关 off/on；
  - v0.2.14 安装/启动。
- 缺口：
  - 无 `CurrentTrackBridge` 单测 / Domain adapter 集成测试；
  - 无“A 分离中 → 自动下一首 B → B 快速分离”真机回归；
  - 无 full 后台完成时当前是 B 的 UI/attach 回归；
  - 无导出模型目录冲突测试；
  - 无取消/播放结束的真实服务事件回归。

---

## 12. 维修计划（初稿，下一步细化）

按风险/收益排序：

### P0（先修，防错曲结果）

1. `CurrentTrackBridge` 临时名唯一化（uri/job/hash），压缩源直接解码 content URI/fd，减少第一层复制。
2. `SeparationDomain.start()` 校验 request.trackKey == currentTrackKey；不一致拒绝并提示重试。
3. `tickEdge`/`onRepoState(EdgeDone)` 校验 edge 会话 source/generation 与 job 一致；不一致丢弃。
4. repo 终态增加 source/job token；Domain 只接受匹配终态。
5. `SpikeViewModel.applyDomainSnapshot` Finished 按 currentTrackKey 过滤；full 后台结果只缓存不推当前 UI。

### P1（状态/UI 一致）

6. 手动开关改走 Domain（Attach/Detach），`autoAttachSuppressed` 与播放器实际一致。
7. 补 A→B 顺序播放真机回归 + 自动化 reducer/domain 交错测试。
8. 接通 `ServiceLost`/`ProcessRestored` 的实际调用点，补状态对账。

### P2（命名/维护）

9. 导出加模型子目录（如 `<song>/<model>/`），或明确产品决策只保留 song 并记录原因。
10. 清理两层临时文件/旧文件；补缓存目录兼容/迁移策略。
11. 收掉 `SpikeViewModel` 的 `SeparationTask`/`lastExportable` 双适配，统一 Domain View。

---

## 13. 待确认/待复现

- 用户报告的 A/B 错结果：需要一张截图或 `logcat` 抓 `SepDomain/loadMultiTrack/SepRepo`，
  确认命中的 cache hash/文件路径是 A 还是 B；本审计只证明代码存在多条可导致该现象的交错路径，
  未在简单 A→B（A 未分离）路径复现。
- 导出是否应该强制“歌名+模型名”目录，仍是产品决策；当前代码没有模型维度。
- FULL 后台继续（D1）时，当前 UI 是否应该完全静默，还是显示“旧曲后台完成”通知，需产品确认。
