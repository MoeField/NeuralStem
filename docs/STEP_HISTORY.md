# 改动历史 / 里程碑（STEP_HISTORY）

> 记录“改了什么、为什么、怎么验证”，方便后来人理解代码演进。这里不记录内部设备流水号或个人环境信息。

## 版本总览

| 版本 | 重点 |
|---|---|
| v0.2.5 | 192k/24bit OOM、旋转/显示、缓存与任务隔离 |
| v0.2.6 | 状态机收口、Sonic 多轨变速、导出/SAF/队列边界 |
| v0.2.7 | 分离取消/切歌后的状态机门禁 |
| v0.2.8 | release 移除调试入口（移到 `src/debug`） |
| v0.2.9 | 三路逻辑审查后的 P1 修复 + P2 清理 |
| v0.2.10 | P2 收尾：A3 媒体命令、多轨失败回退、边分离状态、backfill、队列/导入 |
| v0.2.11 | edge 完整缓存 fast path、StemMixer 线程收口 |

## 早期阶段（v0.2.5 之前）

- **播放器/波形**：Media3/ExoPlayer 单实例；Compose Canvas 自绘波形，拖动 seek。
- **变速变调**：先接 Media3 内置 Sonic（单轨），150ms 防抖。
- **本地分离**：sherpa-onnx/ONNX Runtime + Spleeter/UVR 模型，结果缓存 WAV。
- **多轨**：真实声音迁移到 `PlaybackService` 内的 `StemMixer`；门面 ExoPlayer 静音。
- **边分离边播**：预分配整曲 WAV，播放器读已写区间；Gate 按分离进度暂停/恢复。
- **模型/导航**：模型库与速度档位；EQ + 导航壳；播放/多轨/EQ 统一到 Service。

## v0.2.5：高采样率、旋转/显示、边界状态

- **192k/24bit OOM**：分离输入流式解码到临时 WAV + `ResamplingFrameSource`。
- **旋转/横屏**：页面状态与布局保持。
- **显示状态**：分离进度/完成态/自动切多轨与状态机对齐。
- **缓存/任务隔离**：任务取消 + join；缓存按 `sourceHash+engine`；校验 WAV 长度。

## v0.2.6：状态机收口 + Sonic 多轨变速

- 媒体键/通知在 Service 同步到 mixer；多轨 AudioFocus 由 Service 持有。
- `MixClock` 使用 mixer 实际位置；Service extras 恢复 Activity 重建状态。
- 分离任务 cancel+join/generation；缓存目录 `-full`；灰区提示；水位线恢复。
- 导出 owner/MediaStore pending/同名覆盖；歌单临时文件+fsync+rename。
- `lastPlayedUri` 用原始 uri；队列 remove/move；重建恢复 `currentListId`。
- SAF 失效曲保留并标记；tree grant 释放；自动化 intent 去重。
- JVM 369；发布 v0.2.6（无 UWA `.so`）。

## v0.2.7：分离任务状态收口

- 修复「取消分离 → 切歌 → 再点分离/取消」仍显示上一首进度。
- 状态机新增 `beginSeparation()`；`onSeparationPreparing` 只更新阶段，
  `startSeparation` 只允许 Preparing→Running，Done/Failed/EdgeDone 只接受当前任务；
  `resetSeparation()` 切歌清理；repo `cancel()` 立即 Cancelled。
- JVM 372（新增取消后迟到事件、切歌 reset、Idle start 三条）。

## v0.2.8：release 调试入口清理

- `SpikeActivity` / `TestCommandService` / `TestPanelActivity` 移到 `src/debug/AndroidManifest.xml`；
  release 不再导出，debug 仍保留。
- R8/资源压缩未开启，留作独立维护分支。

## v0.2.9：逻辑审查修复

三路只读审查（播放/分离/歌单）后的 P1 + 部分 P2：

- 播放：移除 `edgeSeekMs` 残留；多轨 LOOP_ONE 在 `mixEnded` 时重启；
  `clearCurrent` 清队列/currentListId/lastSaved；Service 进度/EQ retry 清理。
- 分离：VM 重建从活跃 repo 状态种子（Preparing/Running/Done）；
  supersede 旧任务终态按 generation 丢弃；边分离解码阶段取消关闭 writer/abort；
  导出/清缓存移出主线程；补齐进度 Running→Preparing 可见；edge 仅 Spleeter。
- 歌单：删除当前曲后立即切新 current；`currentListIdFlow` 同步选中态；
  `recordProgress`/`removeTrackFromList` 的 load-modify-save 整体加锁；
  损坏 JSON 备份去重/限量；文件夹歌单禁止导入单曲；SAF tree 共享 grant 引用计数；
  one-shot token 离开页面清零；旋转保留 tab/展开/设置子页。
- JVM 373。

## v0.2.10：P2 收尾批次

- A3 系统媒体 play/pause/变速：默认命令后按门面意图同步 mixer。
- 多轨加载失败：恢复门面音量/焦点并退多轨，不再“静音无 mixer”。
- 边分离 UI 按歌曲隔离；补齐后 gray 提示按 `frontCovered` 判断。
- 阶段 2 尾块只扩到 `endFrame`，小缺口不重算整曲（单测覆盖）。
- 歌单内容变化同步队列；长导入移入 VM scope。
- 多轨中再次边分离允许切新结果；流式 seek 按整曲时长 clamp。

## v0.2.11：edge 缓存快路径 + 音频线程收口

- edge 完整缓存命中只读 manifest + WAV 头，不再先解码整首源；
  真机 sep_probe 命中 **~58ms**，直接进入文件模式播放。
- `StemMixer` 增加线程代际；`stopThread` join 超时后保留旧线程引用，
  `play()` 不再启动第二个 writer。
- JVM 374；真机验证缓存命中自动切多轨 + heartbeat 推进。

## v0.2.12：R8 / 资源压缩

- 开启 release R8 + resource shrinking；arm64 release **~23MB → 14MB**。
- keep：native 方法/类、`SherpaSourceSeparation$SeparationResult` 构造器、
  `Avs3Bridge` 反射桥、可选 NCM 包、Kotlin/Compose 注解属性。
- 验证：R8 release 安装启动；4-stem native 分离完成（infer≈45s）；
  清缓存+关 edge 后 2-stem native 完成（infer≈18.7s / 7.92×）；2 轨导出；
  EQ 挂到 mixer session；旋转；媒体键 pause/play；签名、无 UWA、无 debug 组件；CI 双绿。

## v0.2.13：edge fast path 自动切轨修复

- 问题：完整 edge 缓存 fast path 可能在 <100ms 内从 Buffering 到 Finished，
  终态与上一次会话相同，AppShell 仅以 edgeState/edgeGate 为 LaunchedEffect key
  时会漏掉新会话 → 不切多轨 → lastStems 为空 → 开关报“无分离结果”。
- 修复：key 增加 repoState（fast path 的 EdgeDone 会变化）。
- 真机验证：极快（缓存命中）→ 自动切多轨、开关显示多轨、导出可用。

## v0.2.14：分离状态模型 Level 2

- 新增纯领域层 `spike/state`：Snapshot/Event/Effect/Reducer/Controller；
  分离状态单写者、事件串行、EDGE 自动 attach、FULL 手动、超时/终态栅栏。
- 新增 Android `SeparationDomain`：Controller + 旧 Repository/EdgeSession 适配；
  UI 启动/取消走 Domain；Domain 自动切轨经 PlayerBridge 落到播放器。
- `PlayerState` 删除 edgePercent/edgeStarved/edgeBeforeStart/edgeStartMs/
  edgeSeparatedFraction/backfillPercent/pendingBackfillMs/separationOn/stemsAvailable；
  统一由 `SeparationEdgeUi` + `isMultiTrack` 派生。
- `SpikeViewModel` 不再订阅 repo→UI；由 Domain snapshot 映射任务/导出/完成提示。
- 4-stem ownerTrackKey 修复，避免新任务误 detach 当前 stems；播放结束/取消/换曲
  接入 Domain 外部事件。
- JVM **433/0**（M1 reducer 52 + M2 controller 7 + 原有 374）；
  真机 edge fast path、4-stem cache/启动、开关 off/on 通过。

## 已知限制 / 后续可做

- AVS3/NCM 参考解码器不入库；没有它时相关格式跳过，不能随 release 分发。
- 真实来电的音频焦点丢失没有真实通话环境验证（焦点请求/丢失逻辑已自动测）。
- 非 1× 速度的后台长播：门面 ExoPlayer 可能先 ENDED，mixer 还在收尾；前台正常。
- release 暂未开 R8；debug 测试入口已移入 `src/debug`；R8/体积优化需单独分支。
- `PlaylistStore` 的 VM 整份快照在极端时序下仍可能写回旧进度（已有锁避免数据丢失，只剩自愈式回退）。
