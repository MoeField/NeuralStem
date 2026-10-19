# 原型验证：完整曲长 WAV 随机写 + ExoPlayer 读

> 目的：验证「边分离边播」重构方案的技术前提（用户提出：按块写入正确位置 + 播放侧判断是否可读）。
> 状态：**待验证**

## 待验证的三个问题

### Q1. WavWriter 能否预分配完整长度并随机写？

当前 `WavWriter` 有两个限制：
- `raf.seek(DATA_START + framesWritten * channels * 2)` → **只能顺序写**
- `close()` 用 `framesWritten` 回填 size → **不支持部分填充**

需要新增随机写能力：
- 构造时 `raf.setLength(DATA_START + totalFrames * frameBytes)` 预分配
- 头部直接写**完整长度**（不依赖 close 时回填）
- `writeAt(samples, absFrame, frames)` 按绝对帧位置写

### Q2. ExoPlayer 读「前段有数据、后段全 0」的 WAV 会怎样？

- 预期：正常播放前段，播到 0 区域是**静音**（不是错误）
- 风险：WavExtractor 可能认为文件损坏 / 提前 EOF

### Q3. 边写边读同一文件是否安全？

- 预期：读写各自独立 fd → 安全
- 风险：文件系统缓存一致性（写完未 flush 时读不到）

## 验证方法

最小可运行实验：
1. 造一个 10s 的 WAV：前 5s 是 1kHz 正弦波，后 5s 全 0
2. 用 ExoPlayer 播，观察：
   - 是否正常加载（duration = 10s）
   - 前 5s 是否出声
   - 后 5s 是否静音（非报错）
   - seek 到 7s 是否成功
3. 边播边写：播到 3s 时再写 5-10s 的数据，看能否读到

## 结论

（待填）
