# 共享 PCM 解码计划 / 进度

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
- [x] Phase 4：当前曲 pin、清缓存联动、tmp 清理、跨进程 LRU（设置页占用显示未做）
- [ ] Phase 5：真机性能、长跑、A/B、发布

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

未接入：

- 波形仍未使用 PcmStore
- 分离仍未使用 PcmArtifact
- 尚未在设备上验证
