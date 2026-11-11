# 改动历史 / 里程碑（STEP_HISTORY）

> 记录“改了什么、为什么、怎么验证”，方便后来人理解代码演进。这里不记录内部设备流水号或个人环境信息。

## 阶段 1–3：播放器、波形、变速变调

- **基础播放**：Media3/ExoPlayer 单实例，`PlayerViewModel` + `PlaybackService`，MediaSession 通知/锁屏。
- **波形**：Compose Canvas 自绘，50 包络点/秒；拖动 seek，与采样率无关。
- **变速变调**：先接 Media3 内置 Sonic（单轨）；速度/音高参数经 150ms 防抖下发，播放中实时生效。

## 阶段 4–5：分离接入与多轨

- **本地分离**：sherpa-onnx/ONNX Runtime + Spleeter/UVR 模型，CPU 推理；分离结果缓存为 WAV，避免重复推理。
- **多轨播放**：真实声音迁移到 `PlaybackService` 内的 `StemMixer`（单 AudioTrack）；门面 ExoPlayer 静音，MediaSession 仍可用。
- **多轨混音**：每轨音量/静音/独奏，`gainProvider` 实时相乘；退出多轨回原曲。
- **边分离边播（Edge Separation）**：预分配整曲 WAV，分离线程顺序写、播放器读已写区间；Gate 根据分离进度暂停/恢复混音器。
- **4 轨**：Spleeter 4-stem / Demucs，导出 4 个 stem + 原曲。

## 阶段 M2/M3：模型、导航、架构统一

- 模型库分组、速度档位（极快/临界/慢/很慢）按真机实测 RTF 自动分簇。
- EQ 接入平台 `android.media.audiofx.Equalizer`，多段数随设备。
- 导航壳（AppShell）与页面拆分；播放器/多轨/EQ 统一到 `PlaybackService`，避免前台/后台两套状态。

## v0.2.5：高采样率、旋转/显示、边界状态

- **192k/24bit OOM**：分离输入不再整首解码成 float 数组，改为流式解码到临时 WAV + 重采样 `ResamplingFrameSource`；192k 分离通过。
- **旋转/横屏**：布局与页面状态在旋转后保持；播放器、分离、混音页联动修复。
- **显示状态**：分离进度/完成态/自动切多轨的展示与状态机对齐；修复完成后仍显示“等待”的边界。
- **缓存/任务隔离**：分离任务切换时旧任务取消并 join；缓存目录按 sourceHash+engine 隔离，校验 WAV 长度。

## v0.2.6：状态机收口 + Sonic 多轨变速

这次是“正确性收尾”，重点不是新功能，而是把播放/分离/队列/导出/SAF 的边界状态补齐。

### 播放与媒体会话

- 媒体键/通知/耳机键命令在 Service 侧同步到 mixer；修复“通知暂停只停了静音门面、真实声音还在放”。
- 多轨时 AudioFocus 由 Service 持有；退出多轨时恢复单轨焦点行为。
- `MixClock`：退出多轨/继续分离时用 mixer 实际位置，而不是门面 1× position。
- Service extras 快照 + `PlayerViewModel` 恢复：Activity 重建后 currentUri/stems/speed/pitch/多轨状态不丢。
- 自动化 intent 用 `rememberSaveable` consumed 标记，旋转/重建不重复 load。

### 分离、缓存、导出

- 分离任务 generation/cancel+join：取消后立刻重试不会出现旧任务回调覆盖新状态。
- 缓存目录改为 `stems-cache/<hash>-<engine>-full`；读缓存前校验 manifest + WAV 实际长度。
- 边分离灰区：seek 到分离起点之前明确提示“此段未参与本次分离（从 xx 起）”，不再无声。
- 边分离起始帧/水位线从 manifest 恢复，续播/切回不从头。
- 导出 owner 绑定：换曲/重新分离后旧结果不可导出；歌单页/分离页按钮状态一致。
- 导出 MediaStore `IS_PENDING` 回滚；同名覆盖（先删旧行再写），不生成 `(1)`。

### 歌单、队列、续播

- 歌单持久化改为 tmp + `fsync` + rename；损坏 JSON 保留 `.corrupt-<ts>`，绝不覆盖成空。
- `lastPlayedUri` 改为队列里的原始 uri（不再写 NCM/AVS3 内部临时 uri），冷启动/高亮正确。
- 歌单删除/排序通知播放器队列：`moveQueue`/`removeFromQueue`，当前曲按 uri 重定位。
- Activity/VM 重建后从歌单反查恢复 `currentListId` 与队列，重建后 next/prev 可用。
- `loadAndPlay` 单曲导入默认清 `currentListId`/队列，避免把进度写进上一个歌单。

### SAF / 容错

- 播放失败的曲目保留在歌单并标记「⚠ 需重新授权」，不再自动删除；队列跳过继续。
- 单曲/文件夹 `takePersistableUriPermission` 失败会提示；删除文件夹歌单释放 tree grant。

### 测试与发布

- JVM 单测扩到 369 条：Sonic 时钟/位置、重采样、MixClock、状态机队列、波形/缓存命名等。
- 设备验收：2/4 轨分离与导出、多轨音量/静音/独奏、边分离灰区、旋转/后台、歌单 move/remove、坏曲标记、Activity 重建恢复。
- 发布 v0.2.6：versionCode 26 / versionName 0.2.6；release 签名；三个 APK 均确认不含 `libAVS3AudioDec.so` / `libVMFPlatForm.so`；CI 单测 + release 构建通过。

## v0.2.7：分离任务状态收口

- 修复「取消当前分离 → 切歌 → 再点分离/取消」后仍显示上一首分离进度的问题。
- 状态机新增 `beginSeparation()`（只有用户显式开始才能从 Idle/Cancelled/Failed 进入 Preparing），
  `onSeparationPreparing()` 只更新阶段，`startSeparation()` 只允许 Preparing→Running，
  Done/Failed/EdgeDone 只接受当前任务；新增 `resetSeparation()` 供切歌清理。
- `SpikeViewModel` 所有开始入口统一走 `beginSeparation()`；collector 只有在 Preparing 时才进入 Running；
  换歌 reset 分离任务态；`SeparationRepository.cancel()` 立即写 Cancelled。
- JVM 单测 372/0（新增取消后迟到事件、切歌 reset、Idle start 不产生 Running 三条）。

## v0.2.8：release 调试入口清理

- `SpikeActivity` / `TestCommandService` / `TestPanelActivity` 从 `src/main` 移到
  `src/debug/AndroidManifest.xml`；release 不再导出这三个组件，debug 构建仍保留。
- R8/资源压缩仍未开启；作为后续独立维护分支处理（注意反射与 native/JNI）。
- 验证：release merged manifest/APK 无这三个组件；debug manifest 仍有；安装/启动正常。

## 2026-11-11 逻辑审查修复（v0.2.8 后）

三路只读审查（播放/分离/歌单）后修复的高优先级逻辑：

- 播放：移除残留 `edgeSeekMs`（多轨 carry-over 不再污染下一次单曲 seek）；
  多轨 LOOP_ONE 在 `mixEnded` 时用 `play()` 重启当前曲；`clearCurrent`
  同时清队列/currentListId/lastSaved。
- 分离：VM 重建时从活跃 repo 状态种子（Preparing/Running/Done），避免卡 Idle；
  supersede 时旧任务的 Cancelled/Failed 写入按 generation 丢弃；
  边分离取消发生在解码阶段时关闭 writer/abort，避免 phantom session；
  导出/清缓存移到 IO 线程。
- 歌单：删除当前曲后立即切到新的 current 或清空；播放队列所属歌单通过
  `currentListIdFlow` 同步到歌单选中态；`recordProgress` 的
  load-modify-save 整体加锁；one-shot 自动分离/模型库 token 在离开目标页时清零；
  切换 tab 在旋转后保留。

## v0.2.9：逻辑审查修复

- 播放：移除残留 `edgeSeekMs`；多轨 LOOP_ONE 在 `mixEnded` 时重启当前曲；
  `clearCurrent` 清队列/currentListId/lastSaved；Service 进度/EQ retry 清理。
- 分离：VM 重建从活跃 repo 状态种子（Preparing/Running/Done）；
  supersede 旧任务终态按 generation 丢弃；边分离解码阶段取消关闭 writer/abort；
  导出/清缓存移出主线程；补齐进度 Running→Preparing 可见；edge 仅 Spleeter。
- 歌单：删除当前曲后立即切新 current；`currentListIdFlow` 同步选中态；
  `recordProgress`/`removeTrackFromList` 的 load-modify-save 整体加锁；
  损坏 JSON 备份去重/限量；文件夹歌单禁止导入单曲；SAF tree 共享 grant 引用计数；
  one-shot token 离开页面清零；旋转保留 tab/展开/设置子页。
- JVM 373/0；release 构建通过。

## v0.2.10：P2 收尾批次

- A3 系统媒体 play/pause/变速：默认命令执行后按门面意图同步 mixer
- 多轨加载失败：恢复门面音量/焦点并退出多轨，不再“静音无 mixer”
- 边分离 UI 状态按歌曲隔离；补齐后 gray 提示按 frontCovered 判断
- 阶段 2 尾块归一只到 endFrame，不重算整曲；单测覆盖
- 歌单内容变化同步播放队列；长导入移入 VM scope
- 多轨中再次边分离允许切到新结果；流式 seek 按整曲时长 clamp

## v0.2.11：edge 缓存快路径 + 音频线程收口

- edge 完整缓存命中只读 manifest + WAV 头，不再先解码整首源；真机 sep_probe
  命中耗时 ~58ms（01:22:40.043 → .101），直接进入文件模式播放。
- StemMixer 增加线程代际；stopThread join 超时后保留旧线程引用，play 不再启动
  第二个 writer，旧线程卡在 AudioTrack.write 也不会复活。
- 真机验证：缓存命中自动切多轨、StemMixer heartbeat 推进正常。

## 已知限制 / 后续可做

- AVS3 参考实现不在仓库；本地可选接入，不能随 release 分发。
- 真实来电焦点丢失只做了焦点请求/丢失逻辑的自动验证，没有真实通话环境。
- 非 1× 后台长播的 MediaSession 生命周期只有短时测试。
- release 暂未开 R8；debug 测试入口已移入 `src/debug`，release 不含这些组件；后续如收紧包体需单独处理反射和可选 native 库。
