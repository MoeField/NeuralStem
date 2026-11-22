> **历史快照（已归档，2026-11-23）**：本文是当时过程记录，不代表当前状态；“未修/待办/未跑/当前”等均指当时语境。当前状态见 [REMEDIATION_STATUS](../REMEDIATION_STATUS.md)、[STEP_HISTORY](../STEP_HISTORY.md)。

# 共享 PCM 解码计划 / 进度

> **阅读顺序说明**：以下章节按“追加时间”排列，不是逻辑顺序。
> Phase 1 实际最早完成、所以写在最后；Phase 2/3/4 是后续追加。
> 文中 Phase 1 的“未接入”是当时状态，最终 v0.2.21 已全部接入。

目标：同一首歌在一次播放周期内，波形与分离最多解码一次；分离复用波形/后台预解码产物。

## 架构

```text
SourceDescriptor
      │
      ▼
  PcmStore（单飞 / 租约 / LRU / 磁盘缓存）
      │
      ▼
  PcmDecodePipeline（MediaExtractor + MediaCodec → WAV + envelope）
      │
      ├─ WaveformData（外圈）
      └─ PcmArtifact（分离 / edge）
```

## 数据约束

- `SourceDescriptor.cacheKey` 稳定标识；WAV 不走 PCM cache。
- `PcmArtifact` 是完整落盘的 WAV；临时文件完整结束才 rename。
- `PcmLease` 引用计数；使用中/pin 的文件不允许 LRU/clear 删除。
- 同一 key 同时只有一个 decoder（`PcmStore` 单飞 `Deferred`）。
- 失败/取消必须删除临时文件；分离可回退现有 `decodeToTempWav`。

## 阶段

- [x] Phase 1：`PcmStore` + `PcmLease` + 单飞 + LRU + JVM 测试
- [x] Phase 2：`PcmDecodePipeline`，波形接入
- [x] Phase 3：分离/edge 接入 PCM cache
- [x] Phase 4：当前曲 pin、清缓存联动、tmp 清理、跨进程 LRU、设置页 PCM 占用/清理（v0.2.21）
- [x] Phase 5：真机 PCM 命中/2/4-stem/edge 验证；随 v0.2.20 发布（设置页占用显示仍待做）

## Phase 2/3 已完成

- `PcmDecodePipeline`：MediaExtractor/MediaCodec → 完整 WAV + 渐进包络
- WaveformExtractor 优先读 PcmStore；包络缓存命中时仍后台预解码
- SeparationRepository 的输入优先 `PcmStore.acquire`；分离期间持有 lease
- 真机 bullet.flac：
  - `PcmDecode start/done`
  - `Waveform: waveform(pcm reusable)`
  - 分离 `PCM cache HIT pcm-...wav`，无 `decodeToTempWav`
- 真机验证共 **461/0**

## Phase 4 已完成

- `PlayerViewModel` 在当前曲 pin PCM，换曲 unpin
- `SpikeViewModel.clearCache` 同时清理未使用 PCM；当前曲被 pin 时保留
- `PcmStore` 启动清理 1h 前 tmp、跨进程遗留文件参与 LRU
- 真机：清 stem cache 后 PCM 仍命中；`pcm cache cleared: 0`（当前曲 pin）
- 设置页新增「PCM 缓存」：占用/文件数/12GB 上限/清理未使用
- 清理保护当前曲 pin 与正在解码的 tmp；清空后文件数可为 0

## Phase 1 已完成

新增：

- `player/pcm/SourceDescriptor.kt`
- `player/pcm/PcmArtifact.kt`
- `player/pcm/PcmProgress.kt`
- `player/pcm/PcmLease.kt`
- `player/pcm/PcmStore.kt`

测试：

- `PcmStoreTest` 6 条：单飞、跨 store 命中、lease 阻止清理、pin 保护、失败重试、WAV 不缓存
- JVM 全量 **458/0**

Phase 1 当时未接入（后续 Phase 2/3/4 已接入，见上方追加记录）：

- 波形当时未使用 PcmStore
- 分离当时未使用 PcmArtifact
- 当时尚未在设备上验证
