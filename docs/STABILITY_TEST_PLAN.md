# 状态稳定性测试方案

> 状态：**历史模板**（v0.2.55 门禁已执行，报告见 [STABILITY_REPORT_v0.2.55.md](STABILITY_REPORT_v0.2.55.md)）。  
> **最新实跑**：见 [STABILITY_REPORT_v0.2.65.md](STABILITY_REPORT_v0.2.65.md)（2h 多轨固定 soak + 长分离 + PCM 租约）。  
> 本文保留为可复用的操作字母表/故障注入模板，不代表当前版本门禁。

## 1. 测试目标

不是追求“每个操作都点一遍”，而是验证以下**状态不变量**在所有操作序列下成立：

| 不变量 | 判定 |
|---|---|
| 单播放真源 | 任一时刻只有一个可听输出；不会两首歌/两套 stem 同时响 |
| UI/声音一致 | `isPlaying=true` 时必须存在 StemMixer heartbeat；暂停/停止后必须无声 |
| 位置单调 | 非 seek/loop 情况下位置不回退；曲尾能自然结束或循环 |
| 模式一致 | 单轨/多轨、队列、repeat、speed/pitch、EQ 不互相覆盖 |
| 当前曲一致 | 播放 uri、通知标题、波形、封面、分离目标属于同一首 |
| 波形完整 | 波形长度/峰值与音频匹配；失败要有可见错误，不显示半截/空波形冒充成功 |
| 分离一致 | job 状态、coverage、可播区域、导出文件三者一致；Stopping 必须终止 |
| 数据安全 | 歌单增删/排序/进度在重启、后台、损坏文件场景不丢不覆盖 |
| 授权安全 | 删除/清空后 SAF grant 数量下降；失效曲可重新授权 |
| 异常可解释 | 不支持格式/无权限/缺模型/磁盘满时给明确错误，不静默 ENDED |

## 2. 状态模型

被测状态可抽象为：

```text
PlayerState
  currentUri, playWhenReady, isPlaying, positionMs, durationMs,
  repeatMode, speed, pitch, isMultiTrack, queue[index]

SessionState
  mode(Single/MultiTrack), queue, queueOwner, sessionGeneration

SeparationState
  jobId, lifecycle(Preparing/Running/Stopping/Finished/Failed),
  operation, coverage, files, attachedJobId, edgeSession

PersistenceState
  playlists, selectedList, lastPlayed, grants,
  stemCache, pcmCache, waveformCache

AudioState
  AudioTrack.playState/sessionId, heartbeat, focus, volume, mute/solo
```

测试 oracle 用“**期望状态 + 不变量**”判断，不依赖 UI 文案。

## 3. 操作字母表

### 播放

- `load(uri)` / `clear`
- `play` / `pause` / `toggle`
- `seek(0 / middle / near-end / beyond-end)`
- `next` / `prev` / `queue-end` / `queue-start`
- `repeat(seq / all / one)`
- `speed(0.5..2.0)` / `pitch(-12..12)` / `reset`
- `eq(on/off, band edits)`
- 切后台 / 回前台 / 锁屏 / 通知控制 / 蓝牙断开 / USB DAC 插拔
- Activity 旋转/重建、进程被杀后冷启动、系统回收

### 多轨

- `loadMultiTrack(2/4 stems)`
- `exitMultiTrack`
- 单↔多来回切
- 每轨 volume 0/1、mute、solo、全 mute
- 多轨 seek / speed / pitch / pause / 曲尾
- 多轨中切歌 / 清空 / 退出

### 分离

- edge 快速档、4-stem 快速/质量、Demucs
- 分离中切歌、取消、清缓存、退出页面、回前台、杀进程
- 完成→attach→退出→再 attach
- 成本门控跳过 backfill → 导出
- 导出到 Music/separate、同名覆盖、无权限、磁盘满
- 半截 stem/损坏 manifest/缺失文件
- AVS3/AV3A m4a、NCM、普通 AAC/ALAC/FLAC/MP3

### 歌单/持久化

- 新建/删除/清空/重命名
- 导入单曲/文件夹、删除曲、排序、再导入
- 删除当前曲/当前歌单
- SAF grant 释放/撤销/重新授权
- 损坏 playlists.json、空文件、只读文件
- 冷启动续播、进度记忆、队列恢复
- 旋转/后台/进程重启后继续

### 故障注入

- `StemMixer.failNextWriteForTest`
- `separationTestSlowMs` + 二次 Start
- 删除/截断 stem、PCM、manifest、playlist
- `revoke` SAF grant
- 无模型、错误 SHA、下载中断、磁盘满、无网络
- 强制 `kill -9` / force-stop / 低内存

## 4. 关键确定性序列

每条必须在真机跑，并保留日志/截图/状态快照：

1. 冷启动 → 播放 → 暂停 → seek 多次 → 切后台 10s → 回前台 → 曲尾
2. 单曲播完 → LOOP_ONE → play @0；再切 LOOP_ALL 到队列尾
3. 多轨进入 → 全 mute → 等 5s → 位置必须推进 → 退出
4. 多轨中切歌 → 不可两首同时响 → 原曲恢复
5. 分离完成 → attach → 退出多轨 → 再 attach
6. 分离中切歌 / 分离中取消 / 分离中清缓存
7. 成本门控半截结果 → 导出按钮必须不可用
8. 波形提取中切歌/清缓存/旋转；波形不得串曲
9. 删除当前曲/当前歌单 → 队列/播放器/界面一致
10. 撤销 SAF grant → 播放失败 → 重新选择 → grant 恢复
11. 损坏 playlists.json → 启动/切歌/进度写 → 原文件保持，有备份
12. 写入失败注入 → AudioTrack 重建 → 新 session → EQ 重挂 → heartbeat
13. 强制 stopSelf/Service lost → 恢复或明确错误，不永久 busy
14. AV3A m4a → 明确提示，不静默 ENDED
15. 进程被杀 → 冷启动恢复队列/进度/多轨状态（若支持）

## 5. 随机序列 / 模型测试

### JVM 层

- 对纯状态机/守卫做 **模型测试**：
  - 随机生成 10k 条事件（load/play/pause/seek/queue/start/cancel/finish/stop/attach/clear）
  - 每个状态断言不变量（单 job、Stopping 有 pending、coverage 单调、attachment 匹配）
- 对 `PlaylistStore` 随机操作序列 + 损坏注入，断言数据不丢。
- 对 `WaveformCache`/`PcmStore` 随机 lease/clear/trim，断言不删除使用中文件。

### 真机 adb fuzz

用 TEST_HOOKS 构建，脚本随机组合（脚本：`tools/stability_soak.sh`）：

```text
load / play / pause / seek / next / prev / repeat / speed / pitch /
inmt / exitsmt / edge / cancel / clearcache / dlmodel / kill/restart
```

每个操作间随机 100–1500ms，跑 **至少 2 小时**；每隔 10s：

- 抓 `dumpsys media_session`
- 抓 `dumpsys audio`
- 抓 logcat 关键 tag
- 断言 heartbeat 间隔 < 5s（播放态）
- 断言无 FATAL/ANR/两套 AudioTrack 同时活跃

## 6. 错误分类与取证

| 类别 | 现象 | 关键证据 | 常见原因 |
|---|---|---|---|
| 播放卡住 | UI playing，位置不动，无 heartbeat | `StemMixer heartbeat` 停止、`renderLoop 异常`、`AudioTrack.write` | writer 死/重建未 play、AudioTrack session、Service 丢失、focus |
| 波形异常 | 空白/半截/串曲/长度不符 | `WaveformCache`、`PcmDecode`、`no audio track`、waveform token | 缓存损坏、SAF 权限、解码失败、清缓存竞态 |
| 歌单错误 | 丢曲/重复/顺序错/进度回退 | `PlaylistStore`、`released grant`、`saveMerged` | 合并覆盖、损坏写回、并发保存、SAF 失效 |
| 歌曲错误 | 放错歌/两首同时/静默 ENDED | `loadAndPlay`、`currentUri`、`STATE_ENDED` | 队列/当前曲不一致、AV3A、权限、unsupported codec |
| 分轨异常 | 轨顺序错/时长错/静音/导出半截 | `Spleeter4Sep`、`DemucsSep`、`coverage`、`exportableFiles` | shape 校验、cache 完整、coverage.complete、attach 竞态 |
| 状态死锁 | busy 永久 true、Stopping 不退 | `SeparationDomain`、`JobStopped`、`cancel watchdog` | activeJobId、Service 启动竞态、无 timeout |
| 数据/授权 | 歌单丢失、grant 泄漏 | `persistedUriPermissions`、corrupt backup | 写回、release flags、启动清理 |

每个失败必须记录：**操作序列、时间戳、logcat 片段、状态 dump、是否可复现、根因、修复 commit、回归测试**。

## 7. 测试矩阵

| 层级 | 覆盖 | 执行 |
|---|---|---|
| JVM | reducer/guards/store/cache/hash | CI 每次 |
| androidTest | 多轨引擎/静音/grant/波形 | 真机，发版前 |
| adb soak | 2h 随机操作 + 强制重启 | BF00A 主设备 |
| 设备矩阵 | Android 16 / 14 / 7.1（若 ABI 支持） | 发版前 smoke |
| 人工 | 听感、双轨/多轨、导出文件 | 每次候选版 |

## 8. 发布门禁（v0.2.55）

全部满足才允许打 tag：

- [ ] JVM 全绿（当前 503+，新增测试后更多）
- [ ] androidTest 全绿（至少多轨/静音/SAF/波形）
- [ ] 2h soak 无 crash/ANR/永久卡住/双声
- [ ] 15 条关键序列全部通过
- [ ] 波形/歌单/分离/导出错误均有明确提示，无静默失败
- [ ] 失败项已修复并有回归测试或文档记录
- [ ] release 签名/APK 无 AVS3/AVS3、F-Droid index 校验
- [ ] 测试报告写入 `docs/STABILITY_REPORT_v0.2.55.md`

## 9. 版本规则

- **v0.2.55**：只做稳定性修复/测试，不加新功能；门禁全绿才发。
- **v0.3.0**：若下一版包含 B1 状态架构拆分（PlaybackConnection/Queue/MainActivity/AppShell）或行为边界变化，直接跳到 v0.3.0。
  - 不因为“版本号好看”跳；因为**架构/行为级别变化**跳。
  - v0.3.0 同样跑完整门禁，不降级测试标准。
- 若 v0.2.55 后只有小修补，则 v0.2.56，而不是 v0.3.0。
