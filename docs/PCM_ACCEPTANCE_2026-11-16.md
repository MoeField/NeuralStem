# PCM 共享解码验收记录

> 版本：v0.2.20  
> 设备：`TLF2609A004328`  
> 设置：音量 1/15，EQ 5 段 -15dB  
> 分支：`test/pcm-acceptance`

## 自动化

- JVM：**465/0**
- 新增 `PcmStore` 边界测试：
  - pin 在 prepare 之前
  - 3 个并发 acquire 只解一次
  - trim 清理跨进程遗留 PCM
  - LRU 淘汰未使用最旧 PCM，但保留 pinned
  - lease/clear/pin 已有覆盖

## 真机已通过

1. **首次播放 + 波形/PCM**
   - `bullet.flac`：`PcmDecode start/done`
   - `Waveform: waveform(pcm reusable)`
   - PCM 文件落盘，后续命中
2. **首次播放立即分离**
   - `who_am_i.flac`：分离等待同一个 PcmDecode
   - 完成后 `SepRepo: separation input: PCM cache HIT`
   - 没有第二次 `decodeToTempWav`
3. **4-stem 完整**
   - `Spleeter4` 启动前命中 PCM
   - infer≈39.8s
   - 完成后自动 `loadMultiTrack` 4 stems
4. **清 stem cache**
   - `clearCache: removed 4 cache dir(s)`
   - 当前曲 PCM 仍 `PCM cache HIT`
5. **进程重启**
   - force-stop/start 后再次分离
   - PCM 命中，无重新解码
6. **edge**
   - PCM 命中后正常启动

## 本轮新增通过

### A→B + B 极快

- 12:44:14 `loadAndPlay who_am_i.flac`
- 12:44:19 `loadAndPlay bullet.flac`（自动下一首）
- B 极快：`SepRepo edge 缓存命中`
- 没有把 A 的 PCM/结果给 B；B 日志与当前曲均为 bullet

### PCM 解码中切歌

- 12:44:38 `太空旅行.mp3` PcmDecode start
- 12:44:40 切到 bullet
- 12:44:59 太空的 PcmDecode 继续完成
- 新曲 waveform 正常，无旧波形串入、无崩溃

### PCM 解码中取消分离

- `Good Night.mp3` 首次 PcmDecode + 立即分离
- 点取消：UI「分离已取消」
- PcmDecode 继续到完成，无崩溃/无重复解码日志

## 未完成 / 不可用

- NCM / RawBytes：release 构建未启用 NCM，无法在 v0.2.20 上验证
- 12GB 上限真实压测
- 2–4 小时 PCM 版本长跑
- 设置页 PCM 占用展示

## 观察

- 192k 源 PCM WAV 明显较大（bullet 约 113MB/148s）；默认 12GB 上限约百首量级
- 分离侧日志 `wav @192000Hz → ResamplingFrameSource`，PCM 复用但仍有重采样成本

## 结论

核心 PCM 复用路径通过；尚不能称为完整验收。下一步补 A/B、切歌取消、长跑和 NCM 回退。
