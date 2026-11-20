# NeuralStem 架构与不变量

> 目的：把本项目最容易回归的边界写成可检查的规则，而不是散落在代码注释和事故复盘里。  
> 本文只描述**当前实现**与**被否决的方案**；不是通用 Android 教程。

## 1. 分层

```
Compose UI (AppShell / screens)
  ├─ PlayerViewModel        UI 状态 + MediaController 控制
  ├─ PlaylistViewModel      歌单编辑/导入/持久化
  └─ SpikeViewModel         分离页面状态/MultiTrack 载入
        │
        ├─ SeparationDomain       单 actor + 纯 reducer + repository 适配
        │     ├─ SeparationReducer   纯函数状态迁移
        │     ├─ SeparationRepository 解码/分离/缓存（IO）
        │     └─ EdgeSeparationSession 边分离 ring buffer/门控
        │
        ├─ PlaybackService        MediaSession + ExoPlayer 门面 + EQ
        │     └─ MultiTrackController
        │           └─ StemMixer  单 AudioTrack + 单渲染线程
        │
        └─ 持久化
              ├─ PlaylistStore      playlists.json
              ├─ RangeManifest      MANIFEST.json
              ├─ PcmStore           pcm-cache/
              ├─ ModelDownloader    models
              └─ SettingsRepository SharedPreferences
```

原则：

- UI 不直接持有播放/分离真值，只观察 Flow 并下发意图。
- 解码、分离、文件校验、网络全部在 IO 线程。
- MediaSession 是**系统集成输出**，不是播放状态真源。

## 2. 播放侧不变量

### I1：多轨只有一个音频时钟

- `StemMixer` 拥有唯一 `framePos`，一个 `AudioTrack`、一个渲染线程。
- 所有 stem 必须从同一个 `framePos` 读取；不允许“每轨一个 ExoPlayer/时钟”。
- **被否决**：每条 stem 一个 ExoPlayer。实测起播错位 800ms、seek 校正 105 次仍 890ms，且 underrun 时 `isPlaying` 反复翻转。

### I2：AudioTrack 重建必须同时恢复播放和 session

`write < 0` 后重建 AudioTrack 时：

1. `release()` 旧 track，置空；
2. `ensureTrack()` 新 track；
3. 必须 `play()` 新 track（默认 STOPPED，否则下一次阻塞写永久卡死）；
4. 新 `audioSessionId` 必须通知 `PlaybackService`，由 `EqController.detachAll()/attach()` 重挂 EQ。

**反例**：只 `ensureTrack()` 不 `play()`；或只重建 track 不重挂 EQ。  
**测试缺口**：write 失败注入目前靠代码审查/真机异常，没有自动化注入点。

### I3：曲尾重启不能丢 writer

- 曲尾时渲染线程设 `ended=true`、`running=false` 后退出。
- `play()` 遇到旧线程 `alive && !running` 时，先等待其退出，再启动新线程。
- 若 300ms 内仍未退出，保持 `playing=false`，让 UI 回到暂停，不能假装在播。
- **被否决**：看到 `thread.isAlive` 就直接 return；会出现“UI playing、mixer 无 heartbeat”。

### I4：全静音不等于无数据

- 所有轨 `gain=0` 时仍必须从 canonical 源读取并写零，推进 `framePos`。
- `ended` 只能由 `framePos >= totalFrames` + 源终态决定，不能由 `framesRead=0` 决定。
- 但要区分“全静音”和“边分离未追上”：后者应等待，不推进。

### I5：isPlaying 按模式单一来源

| 模式 | 真源 | 允许写入者 |
|---|---|---|
| 单轨 | ExoPlayer 门面 | `PlayingSource.FACADE`、`EXPLICIT` |
| 多轨 | `StemMixer` 经 session extras | `PlayingSource.MIX`、`EXPLICIT` |

- 多轨下门面 `onIsPlayingChanged` 只能记日志，不能改 UI。
- 单轨下旧 mix extras 不能改 UI。
- 所有写入走 `updatePlaying()`；禁止新增 `_state.copy(isPlaying=...)` 分支。
- **被否决**：门面 listener 与 200ms extras 各自直接写 `_state`。

## 3. 分离侧不变量

### J1：只有一个写者

- `SeparationController` 是单 actor：所有事件串行进入纯 `SeparationReducer`。
- 外部只发 `SeparationEvent`，不直接改 snapshot。
- Effect 由 Domain 执行；执行结果再作为 Domain 事件回来。

### J2：Stopping 必须可终止

- `activeJobId` 由 `StartDecode` 设置，**不能在 `start()` 里提前清空**。
- 否则旧 job 的 `Cancelled/JobStopped` 会被 `onRepoState()` 的 `activeJobId ?: return` 丢弃，UI 永久 busy。
- `CancelJob` 必须有 watchdog：2s 后仍处同一 Stopping → 补发 `JobStopped`。
- **被否决**：只清 `pendingStart` 当取消；或依赖一个永远没有发送者的 `OperationTimeout`。

### J3：coverage.complete 才能 attach/export

- `Finished` 只表示任务结束，不表示全曲可播/可导出。
- 成本门控跳过 backfill 时，`coverage.complete=false`。
- `exportableFiles()`、attach、导出按钮都必须检查 `coverage.complete`。
- **被否决**：只看 `Finished + files != null`。

### J4：decode 目标必须用 generation 守卫

- 每次 `loadAndPlay` 递增 `loadGeneration`。
- AVS3/NCM 解码完成时必须 generation 未变 + session 目标仍是本曲，才允许 `doLoad`。
- 普通曲目加载时要取消旧 decodeJob 并清 `session.avs3Decode`。
- **被否决**：只校验 URI；普通路径不会改写 session 状态。

## 4. 持久化不变量

### K1：歌单保存必须合并进度

- `PlaylistStore.saveMerged()` 在 `IO_LOCK` 内重新 `load()`，磁盘上的进度优先。
- VM 内存快照不得整份覆盖播放器刚写入的续播位置。
- 空快照/未加载完成/损坏标记期间拒绝保存。
- **被否决**：VM 直接 `store.save(_lists.value)`。

### K2：损坏文件只读

- `playlists.json` 解析失败 → 备份 `.corrupt-*`，置 `corrupt` 标记，内存返回默认歌单。
- 标记期间 `save/saveMerged/recordProgress` 全部拒绝。
- 必须提供恢复入口（当前待补 UI）。
- **被否决**：备份后继续让下一次 `recordProgress` 覆盖原文件。

### K3：缓存文件以内容完整性为准

- WAV 缓存必须校验 RIFF header 的 data chunk 长度与实际文件长度一致。
- 只有 `length>0` 不是可复用依据。
- `RangeManifest` 先 fsync 音频、再 fsync manifest，最后 rename；不能先 delete target。
- **被否决**：用文件大小判断“已分离完成”；用 `hashCode` 做波形缓存名。

### K4：SAF 授权有归属

- tree grant 按系统实际的 READ/WRITE 位释放，不能固定 READ|WRITE。
- 单文件 grant 只在没有其他歌单引用时释放。
- 授权丢失只标记“需重新授权”，不从歌单删除。
- **被否决**：全量复制导入文件来规避授权上限（1000 首会双份存储，且改变文件语义）。

## 5. 发布与安全

### L1：发布身份链

- tag 构建必须正式签名，缺密钥直接失败。
- `versionCode` 从 tag 派生；CI 校验 APK versionName/versionCode/证书 SHA-256。
- APK 不得含 `libAVS3*`/`libVMF*`/`libav3a*`；`with_ncm` 必须单独编译/测试。
- **被否决**：缺密钥回退 debug 签名；手写 versionCode 不跟 tag 同步。

### L2：外部输入边界

- `MainActivity` 的 `auto_play/mt_vocals/eq_test/second_file` 自动化入口只在 `BuildConfig.DEBUG` 生效。
- MediaSession 自定义命令只接受本应用 UID；外部 controller 不暴露 custom commands。
- NCM shell 路径参数走 argv，不拼 `sh -c`。
- 模型下载必须校验 SHA-256；默认直连，第三方代理需显式 opt-in。
- 备份排除模型、stems/PCM/波形缓存、`playlists.json`。

## 6. 测试策略

- **纯逻辑优先 JVM**：reducer/guards/merge/parser/hash/WAV 校验。
- Android 相关（AudioTrack、MediaSession、SAF grant、Application 生命周期）需要设备/仪器测试；当前没有 CI 仪器测试，是已知缺口。
- 每个事故至少留一个**回归测试或文档不变量**；不要只靠注释。

## 7. 维护规则

1. 修改上述不变量时，**同一提交内更新本文**。
2. 新增跨线程写入、缓存格式、发布步骤时，先问“谁拥有真值/谁负责清理”。
3. 不把“临时调试/自动化入口”放进 release 路径。
4. 外部参考项目只读设计；GPL 代码不进入 MIT 项目。
