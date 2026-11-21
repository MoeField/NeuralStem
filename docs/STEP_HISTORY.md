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

## v0.2.50：B2 生产 UI i18n

- Settings 的 NCM/解码缓存/PCM 缓存二级页文案全部迁 `values/strings.xml` + `values-en`。
- 歌单页导入/授权/重新选择/再加载文案迁资源。
- SpikeViewModel 的导入/解析/取消错误文案迁资源。
- 未改逻辑/布局；普通 503/0；lint 0 error。
- 说明：分离 Domain/Reducer 内部的错误串仍为中文常量（无 Context，需后续通过消息 ID 映射）；日志/测试命令不迁。

## v0.2.50 准备：C 项测试注入（未发版）

- 新增 TEST_HOOKS `separationTestSlowMs`：edge 首块前可注入延迟。
- `sepdouble` 命令在首块前停 3s，尝试稳定复现 Running→Stopping。
- 实测仍被 `SeparationService` 启动窗口干扰：第二次 Start 前 Service 已 stopSelf，Domain 得到“分离服务丢失”，未录到稳定 Stopping。
- 未带未经证实的 Service 改动进 release；只保留测试注入，release 默认 0。

## v0.2.49：SAF 重新授权真机修复

- **#13 真机验证**：导入→grant=1；删除→grant=0 且日志 `released file grant`；revoke→重启后播放失败并显示“⚠ 需重新授权”；重新选择→标记消失、播放成功、grant=1。
- **真实 bug 修复**：系统文档选择器可能触发 Activity 重建，`remember` 的 `reauthTarget` 丢失，表现为“授权成功但曲目未替换”。改为 `rememberSaveable` 保存 `reauthListId/reauthOldUri`。
- 新增 TEST_HOOKS 命令 `grants`/`revoke`（release 不暴露）。
- 未新增 JVM 测试；普通 503/0。

## v0.2.48：验证补齐与剩余 P1/P2 收尾

- **#1** 新增 test-only `StemMixer.failNextWriteForTest` 注入；真机强制 write 失败后验证：重建 AudioTrack → 新 session 2817 → EQ 重挂 → heartbeat 继续。
- **#2** watchdog 判定抽成纯函数 + 单测；`sepdouble`/`mixfail` 测试命令（TEST_HOOKS 构建）。
- **#5** 新增损坏 playlists.json JVM 测试：`load()` 后 `recordProgress/save` 不覆盖原文件。
- **#11** `parseTracksJson()` 抽出，单测保留 `uri`；VM 重建恢复路径有回归。
- **#12** `StemMixer.close()` 主线程最多等 50ms，剩余 join/release 交后台 reaper；`load()` 失败不替换数据源。
- **#13** 启动清理孤儿 persistable grant；歌单失效曲新增「重新选择」入口 + `replaceTrackUri()`。
- **P2** `BackfillCostGateTest` 改调生产纯函数；Spleeter4 iSTFT 线程池改 daemon。
- 测试：普通 **503/0**；with_ncm 527/0。

## v0.2.47：模型下载生命周期与断点续传

- **#17** 新增进程级 `ModelDownloadManager`：每个 `ModelPack` 独立 job；离开模型库/Activity 重建不取消下载；取消 A 不影响 B。
- `.part` 不再在启动/取消/失败时删除；`downloadOne` 支持 HTTP Range（`bytes=N-`）、206 校验 `Content-Range`、200 忽略 Range 时从头重写并校正进度。
- 下满但未 rename 的 `.part` 直接校验并落地；SHA-256 失败仍删除重下。
- 取消响应：`HttpURLConnection.read` 阻塞时用 `Job.invokeOnCompletion { disconnect() }` 立即打断；取消后 UI 立即回到可重下状态。
- 真机验证：离开模型库再返回，下载仍在（0%→5%）；取消后立即恢复按钮；再次下载从 1,266,623 B 续传（`Range: bytes=1266623-`，5%）。
- 新增 `ContentRangeTest`；JVM **496/0**。
- 仍为进程内续传；进程被杀后依赖 `.part` + Range 恢复，不做前台服务/通知。

## v0.2.46：JNI 输出形状校验

- **#9 demucs**：C 侧校验 `output=[1,4,4,2048,336]`、`add_67=[1,4,2,343980]` 的 dtype/维度/元素数；错误直接 abort copy。
- **#9 spleeter4**：C 侧校验每个 stem 的 `[2,splits,512,1024]` 四维 shape/dtype/元素数；不再只比总元素。
- Kotlin 二次校验：`checkedDemucsOutputs`/`checkedSpleeter4Outputs` 拒绝 null/错长度数组。
- 新增 6 条 JVM 测试；普通 **495/0**。
- C 侧仍保留 `GetTensorMutableData` status 检查与异常清理。

## v0.2.45：架构不变量文档 + 单源测试

- 新增 `docs/ARCHITECTURE.md`：播放/分离/持久化不变量、被否决方案、维护规则。
- #10 抽出纯函数 `resolvePlayingUpdate()` + `PlayingSourceTest`，锁住“单轨只认门面、多轨只认 mixer”。
- #13 抽出 `grantReleaseFlags()` + `GrantReleaseFlagsTest`，锁住 READ/WRITE 位组合。
- 文档索引/README/DEVELOP 互相链接。
- JVM **489/0**。

## v0.2.44：CI 与供应链门禁

- **#15**：CI 新增 `lintVitalRelease`；不再只跑 lintDebug。
- **#16**：CI 新增 `-Pwith_ncm=true` 的 JVM 测试（24 条 NCM 测试），并移除 `app/src/ncm/**` 的 paths-ignore；APK 发布前断言不含 `libAVS3*`/`libVMF*`/`libav3a*`。
- **#6**：`DEFAULT_GITHUB_PROXY` 改为空（默认直连）；第三方代理必须用户在设置里显式填写；文案明确会暴露 IP/请求路径。
- 本地：普通 486/0；with_ncm 510/0；lintVitalRelease 通过。
- 未做：#9 JNI 输出形状校验、#17 下载任务生命周期/续传（下一批）。

## v0.2.43：P0 第二组（Stopping/缓存/状态/SAF/返回键）

- **#2 Stopping 死胡同**：`SeparationDomain.start()` 不再提前清 `activeJobId`；`CancelJob` 增加 2s watchdog，超时补发 `JobStopped`，避免 `busy` 永久 true。
- **#7 PCM 缓存**：复用/重命名前校验完整 WAV 头与 data 长度；rename→Ready 窗口把 finalFile 纳入 activeTemps，避免清缓存删掉即将发布的文件。
- **#8 分离缓存/WAV 落盘**：`readCache/readCache4/readCacheDemucs` 统一用 `isCompleteWav`；`WavWriter.close()` 补 `fd.sync()`。
- **#10 isPlaying 单入口**：新增 `PlayingSource`/`updatePlaying()`；多轨只认 mix extras，单轨只认门面，显式载入/清空可强制；消除 `_state.copy(isPlaying=...)` 的四处互相覆盖。
- **#13 SAF grant**：单文件 grant 无其他歌单引用时释放；tree grant 按实际 READ/WRITE 位释放，避免 READ|WRITE 合并异常被吞。
- **#18 BackHandler**：非播放器页按返回回播放器，不再直接退出 App。
- 新增 `WavIoStreamingTest` 截断 WAV 校验；JVM **486/0**。

## v0.2.42：P0 第一组（音频/导出/恢复/守卫）

- **#1 AudioTrack 重建**：`write<0` 重建后补 `track.play()`；`StemMixer` 新增 `onAudioSessionChanged`，Service 收到新 session 后重挂多轨 EQ。
- **#3 导出完整性**：`SeparationGuards.exportableFiles()` 要求 `coverage.complete`；半截结果不再可导出；补 guard 单测。
- **#14 全轨静音**：所有 gain=0 时仍从一条 canonical 源读取并写零，`framePos`/`ended` 继续推进；真机两轨全 mute 后位置仍走。
- **#11 B1 恢复**：`applyTracksSnapshot()` 补 `uri` 字段，VM 重建后能还原 lastStems。
- **#4 release 自动化入口**：`MainActivity` 的 `auto_play/mt_vocals/eq_test` 分支加 `BuildConfig.DEBUG` 守卫；release 包真机验证忽略 `auto_play`。
- **#5 损坏歌单**：`PlaylistStore` 置 `corrupt` 标记，期间拒绝 `save/saveMerged/recordProgress`，避免切歌覆盖损坏现场。
- JVM **485/0**；lint 0 errors。

## v0.2.41：多轨曲尾重启竞态热修

- 根因：`StemMixer.play()` 看到旧渲染线程仍 alive 就直接返回，只置 `playing=true`；旧线程曲尾退出后没有新线程 → UI 显示播放但 mixer 无 heartbeat、无声。
- 修复：旧线程 `running=false` 但线程仍存活时，短暂 `join(300)` 等其退出再启动新线程；仍失败则保持 `playing=false`，让 UI 回到暂停。
- 真机复现：2-stem 多轨 seek 到 2:26，自然到曲尾；现在日志 `mix ended → LOOP_ONE` → `StemMixer play @0frame`，后续 5s/10s heartbeat 正常，不再出现死 mixer。
- JVM **485/0**。

## v0.2.40：C 阶段工程卫生（第一批）

- **WaveformCache**：缓存文件名从 32-bit `hashCode` 改 SHA-256 截断，避免碰撞显示别的歌波形；新增 JVM 测试。
- **StemMixer**：渲染线程改用 `THREAD_PRIORITY_AUDIO`，不再 `Thread.MAX_PRIORITY`。
- **死代码**：删除无调用的 `FrameSource.toStereo/takeFirstTwoChannels/decodeToPcm`、`Media3DecodeBridge.decodeToPcm`、`PlaybackService.onPlayerReadyForEq`。
- **CI lint**：新增 `:app:lintDebug` 硬门禁；修复 `MissingClass`、`MissingTranslation`，并禁用误报 `UnsafeOptInUsageError`（代码已用 `@file:OptIn`）。
- JVM **485/0**；lint 0 errors。
- 未完成：i18n 全量迁移、`dispatchTestCommand`/probe 拆 debug 源集、权限 manifest overlay、P3 数组不可变，留 C2。

## v0.2.39：B 阶段隐私与命令边界

- **备份**：`backup_rules.xml` / `data_extraction_rules.xml` 排除模型、stems/PCM/波形缓存、`playlists.json` 和外部 UVR 目录；避免 1.2GB 备份失败与歌单路径上云。
- **MediaSession**：自定义命令只对本应用 UID 暴露；`onCustomCommand` 对非本 UID 直接返回 `ERROR_NOT_SUPPORTED`，外部 App 无法触发加载/EQ/退出。
- **NCM shell**：新增 `shArgv()`，`cat/dd/cp/stat/ls/od` 等带路径命令改 argv；静态 `find` 的目录名做单引号转义，消除引号/`$()` 注入面。
- **模型下载**：GitHub 代理 DNS/连接失败时自动回退直连；`.part` 删除后重试，仍做 SHA-256 校验。
- `with_ncm=true` Kotlin 编译通过；JVM **483/0**。

## v0.2.38：A 阶段数据完整性

- **P2.2 歌单陈旧快照**：`PlaylistStore.saveMerged()` 在 IO_LOCK 内重新 load，磁盘进度优先；VM 异步 persist；空快照/未加载完成拒绝落盘。
- **P2.1 主线程 I/O**：歌单 load/save 移到单线程 IO；新增 `PlaylistStoreMergeTest`。
- **P2.11 RangeManifest**：先 fsync tmp 再 rename，不再先 delete target；JVM 覆盖写入测试因 Android JSON stub 未纳入。
- **P2.12 fd 泄漏**：`WavReader` 构造失败 close source；`decodeToTempWav` writer 放 finally。
- **P2.4 JNI 判空**：sherpa/demucs C 层补数组/指针/异常检查；Kotlin 检查 native null。
- **P2.5 流式 PCM**：流式路径按 `KEY_PCM_ENCODING` 支持 16-bit/float，不再一律 asShortBuffer。
- **P2.3 多轨失败**：`onCustomCommand` 整体捕获，失败时 abort 多轨并回错误，不冒到 MediaSession 主线程。
- JVM **483/0**；设备 smoke：歌单异步加载正常。
- 说明：RangeManifest 文件写入单测受 Android `org.json` stub 限制，未加入 JVM；P2.4 OOM 路径未做真机注入。

## v0.2.37：模型供应链 P2.6

- `ModelFile` 增加官方 `sha256`；覆盖 20 个模型包共 24 个文件（HF LFS `lfs.oid` / GitHub Release asset digest）。
- 下载到 `.part` 后先校验 SHA-256，再原子 rename；不匹配删除临时文件并报错。
- 已有文件复用前也做大小 + SHA-256 校验；损坏文件自动删除并重下。
- 新增 `ModelCatalogIntegrityTest`、`ModelDownloaderIntegrityTest`；JVM **481/0**。
- 真实 HF 文件已复核：`vocals.fp16.onnx` = `24cef8...c7b668`，与目录一致。
- 设备侧真实 UVR 下载被 `api.gitproxy.dev` DNS 拦截，未能完成端到端下载；属于网络问题。

## v0.2.36：发布门禁 P2.7

- `versionCode` 改为 `major*10000 + minor*100 + patch`，由 tag/CI 派生；`v0.2.36` → 236。
- tag 构建强制 `-PrequireReleaseSigning=true`：缺 keystore/密钥环境变量时 Gradle 直接失败，不再回退 debug 签名。
- CI 在 tag 构建校验：
  - APK `versionName` == tag
  - APK `versionCode` == tag 派生值
  - APK 证书 SHA-256 == `EXPECTED_APK_CERT_SHA256`
- 本地无密钥构建仍可 debug 回退（仅供调试）。
- 验证：`-PversionName=0.2.36 -PversionCode=236` 产出 code 236；无密钥 + 强制签名时构建失败。
- JVM **475/0**。

## v0.2.35：StemMixer 关闭顺序

- `StemMixer.stopThread()` 改为先 `pause/flush` 解开可能阻塞的 `AudioTrack.write`，再 `interrupt/join`；避免主线程白等 800ms。
- 真机 2-stem 多轨退出：`EXIT_MT` → `StemMixer closed` 约 11ms，无回归。
- JVM **475/0**。

## v0.2.34：修复 AVS3/NCM 切歌后旧解码抢播

- `loadAndPlay` 每次请求递增 `loadGeneration`；`loadAvs3`/`loadNcm` 完成后必须同时满足 generation 与 session 目标都未变，才允许 `doLoad`。
- 切到普通曲目时调用 `cancelStaleAvs3Decode()`：取消旧 `decodeJob`、清空 `session.avs3Decode`、同步 UI；不再只依赖 URI 判断。
- 旧协程的进度回调也校验 generation，避免旧进度写入新曲的 UI。
- 当前包不是 NCM 构建，无法真机复现 AVS3 路径；静态链路和编译/JVM 回归通过。
- JVM **475/0**。

## v0.2.33：修复三个已复现的分离 P1

- **P1.2 Demucs 短音频越界**：`outInter` 从 `step*2` 扩到 `W*2`，并加 `flushLen <= W` 断言；7s 输入真机验证不再抛 `ArrayIndexOutOfBoundsException`。
- **P1.5 单声道 4-stem 时长翻倍**：4-stem 的 `WavWriter` 固定 2 声道，不再跟随 mono 输入；单声道 10s 导出现在是 `2ch / 10.000s`。
- **P1.4 FULL 分离取消无效**：`StreamingSeparator` / `Spleeter4Separator` / `DemucsSeparator` 增加块级 `isCancelled`；Repository 传入 generation，进度回调也做 generation 校验，避免把 `Cancelled` 覆盖回 `Running`。
- 新增回归：`DemucsFlushBoundsTest`、`StreamingSeparatorCancellationTest`。
- 真机：取消后当前 native 块结束即停，CPU 从 ~400% 降到 0%，不再跑完整首。
- JVM **475/0**。

## v0.2.32：实时进度单源 + 清缓存立即清完成提示

- EDGE 阶段 1 不再同时接受 Repo `Running` coverage 与 `tickEdge` coverage；两套百分比公式交替会导致实时进度回跳/乱跳。
- 清缓存成功后直接清空旧 job（不是保留 files=null 的 Finished job），Domain 不会再重新点亮「✓ 分离完成 · 全曲可播」。
- 完成提示按 jobId 去重；清缓存触发的 snapshot 不会再次显示。
- 真机：实时进度 `17→37→45→55→78→95` 单调；清缓存后绿色完成提示立即消失。
- JVM **472/0**。

## v0.2.31：补前段文案也保持总进度

- 补前段期间文案从「正在补前段 X%」改为「总进度 Y% · 补前段 X%」，数字不再从 83% 跳到 0%。
- 进度条与文案统一使用整体覆盖率；v0.2.29 只修了进度条，文案仍会跳。
- JVM **472/0**。

## v0.2.30：清缓存后旧 stem 引用失效

- 清缓存成功后通知 Domain：旧 `job.files` / `lastFinished` 置空；PlayerVM 同步清 `lastStems` 和波形。
- 领域新增 `StemsCacheCleared` 事件与回归测试；避免清完缓存后开关还能用旧文件 attach。
- JVM **472/0**。

## v0.2.29：补前段进度不再假跳

- 进度条从“阶段 1 覆盖率”切到补前段时，不再重置为 0；改为按剩余缺口合成整体覆盖率。
- 保留「正在补前段 X%」文字，但进度条不会出现 85% → 0% 的假跳变。
- F-Droid workflow 增加 release tag/versionName 与 APK 签名证书校验。
- JVM **471/0**。

## v0.2.28：前段提示稳定 + 清缓存清完成提示

- 前段缺口提示/「补齐前段」按钮改用 EdgeSeparationSession 的稳定 startFrame，不再因新 job coverage=0 闪一下。
- 清缓存成功后立即取消 `lastEdgeDone` 的 5 秒 flash，不再残留「✓ 分离完成 · 全曲可播」。
- 发布链路：F-Droid 自建源改为 Release published 后由 GitHub Actions 自动签名发布。
- JVM **471/0**。

## v0.2.27：自动 attach 后覆盖率继续更新

- 修复 edge 自动 attach 后 `job.operation` 变为 null，`onCoverageUpdated` 仍要求 Separating/Backfilling → 覆盖率冻结在 15%/17%。
- 运行中已 attach（operation=null）也接受 coverage 更新；播放器/波形/分离页进度保持同步。
- 新增 reducer 回归测试；JVM **471/0**。

## v0.2.26：补前段进度显示

- 快速模式阶段 2（补前段）时，分离页 RUNNING 分支仍显示阶段 1 覆盖率（如 15%），看起来进度卡死。
- RUNNING 且 `backfillPercent != null` 时改用补前段进度条和「已可完整播放 · 正在补前段 X%」文案。
- 播放器/波形继续正常更新；JVM **470/0**。

## v0.2.25：快速模式补前段波形

- 修复 edge/快速模式阶段 2（补前段缺口）漏传 `onEnvelope`：音频/manifest 已补齐，但外环前段包络一直为 0。
- 补前段现在与阶段 1 一样写入 `session.appendEnvelope`，波形随补齐进度更新。
- JVM **470/0**。

## v0.2.24：多轨/缓存状态收口

- 2/4 轨结果播放优先走 `SeparationDomain.tryAttach`；兼容旧直接载入路径会同步 attachment。
- 清缓存按钮在多轨播放/分离中禁用，避免删除正在读取的 stem。
- stem 波形增加 session token；旧的 2 轨/旧会话回调不能覆盖新 4 轨。
- Reducer 增加 external PlaybackStemsLoaded / clear cache guard 测试。
- JVM **470/0**。

## v0.2.23：2→4 轨波形/开关修复

- 修复 2 轨 edge 实时包络采集器把 4 轨 `stemWaveforms` 覆盖成 2 项的问题。
- 分离开关关闭时兜底退出多轨：4 轨/2 轨结果直接 `loadMultiTrack` 的路径可能没有 Domain attachment，旧 detach 为 no-op。
- 真机：2 轨→4 轨→清缓存→关开关，回到「当前：原曲」。
- JVM **467/0**。

## v0.2.22：缓存提示布局 + F-Droid 自建源

- 分离页「清理缓存」结果提示移到按钮下一行，横屏/窄屏不再被压成竖排。
- 包名/版本规则：`0.2.22 (42)`。
- F-Droid 自建源正式地址：`https://neuralstem.miku.pub/fdroid/repo/`。
- repo 分类改为 `Local Media Player`；保留原包名与 release 签名。
- JVM **467/0**；无新增业务逻辑。

## v0.2.21：PCM 缓存设置页

- 设置页新增「PCM 缓存」：占用、文件数、12GB 上限、清理未使用。
- 当前播放曲 pin / 正在解码的 active tmp 不会被清理；修复清理后残留 tmp。
- 长跑后设置页验证：当前曲保护、清理计数正确。
- JVM **467/0**。
- 遗留：上限暂不可调；NCM release 未启用，仍未验证。

## v0.2.20：共享 PCM 解码缓存

- 新增 `PcmStore`：单飞解码、`PcmLease` 引用计数、pin、LRU、原子落盘。
- `PcmDecodePipeline`：MediaExtractor/MediaCodec 解一次，写 WAV + 渐进包络。
- 波形优先读 PcmStore；包络缓存命中时仍后台预解码。
- 分离/edge 输入优先 `PcmStore.acquire`，分离期间持有 lease。
- 当前曲 pin；`clearCache` 同步清理未使用 PCM。
- 真机：分离日志 `PCM cache HIT pcm-*.wav`，不再二次 `decodeToTempWav`。
- JVM **461/0**；PCM cache 默认上限 12GB。
- 遗留：设置页未显示 PCM 占用；PCM WAV 保持解码器原始采样率（大文件更占盘）。

## v0.2.19：移除 SpikeVM 分离双适配

- 导出：`SeparationScreen` 改读 Domain snapshot；新增 `SeparationGuards.exportableFiles`。
- 显示：新增 `SeparationUiMapper`（Preparing/Decoding/Running/Done/Failed/Cancelling）。
- 完成副作用：FULL 完成改为 Domain 派生的一次性 `CompletedStems` 事件，按 jobId 去重；
  EDGE 仍由 Domain 自动 attach，保留 `lastEdgeDone` 短提示。
- 删除：`_separationTask` / `separationTask` / `lastExportable` / `exportableOwnerUri` /
  `lastMapped*` / `consumeSeparationDone` 的 VM-UI 适配；`SpikeViewModel` 不再持有
  `SessionStateMachine.separation` 镜像。
- `SeparationScreen` 的进度/文案/取消全部直接读 Domain snapshot。
- JVM **452/0**；真机 4-stem 完成自动切多轨、进度、导出、取消通过。
- `SessionStateMachine.separation` API 仍作为播放器侧历史状态保留，不再被 UI/VM 使用。

## v0.2.18：WAV fd 随机访问

- `WavIo`/`WavReader` 改为 `FileChannel` positioned read：
  - File 仍走本地文件；
  - content URI WAV 走 `ParceFileDescriptor` + `FileChannel`，不再复制原文件；
- `CurrentTrackBridge` 对 WAV content URI 也只写 `.sepmeta`，`SeparationInput`
  优先 `WavReader.fromUri`；仅 fd 不可 seek 时才回退解码临时 WAV；
- 真机：
  - WAV content：`wav content fd: bullet_44k.wav sr=44100 frames=6528376`，无原始副本；
  - file WAV：仍 `wav @44.1k → 流式直读(零解码)`；
  - 压缩源直解码路径不受影响；
- JVM **446/0**；`WavIoStreamingTest` 的 FileChannel 读写往返通过。

## v0.2.17：压缩源直解码（P2-2）

- `CurrentTrackBridge` 对压缩 `content://` 只写 `.sepmeta` 元数据（原始 URI/显示名/扩展/内容 hash），
  不再整首复制 `separate_input_<hash>.<ext>`；
- `SeparationInput` 识别桥接元数据后用 `MediaExtractor.setDataSource(context, contentUri)` 直接解码；
  WAV 仍保留一份本地副本（WavReader 需要随机访问 File）；
- `SeparationRepository.contentHash` 优先读取桥接元数据 hash，保持旧缓存键不变；
- 真机验证：
  - 首次 edge：`TrackBridge: bridged uri ... .sepmeta` → `SepInput decoded bridged uri`，无原始副本；
  - 二次 edge：`edge 缓存命中（fast path，未解码源）`；
  - 4-stem：同样直解码并正常完成；
- JVM **446/0**；新增 `CurrentTrackBridgeMetaTest`。

## v0.2.16：验收修复

- 验收补测：A1/A2/A3/B1/C1/C2/D1/D4/E1/E2/F1/F2 通过；
- 修复验收发现：旧曲 FULL 后台完成时，当前曲分离页残留旧 `Preparing`。
  `SpikeViewModel` 在 job 不属于当前曲时 `resetSeparation()` + `State.Idle`。
- 新增纯函数测试：`CurrentTrackBridge.tempSourceName`、`SeparationGuards.jobMatchesCurrent`、
  `StemExporter.exportRelativeDir`；JVM **443/0**。
- B2（返回原曲恢复）、D2（播放自然结束）已补测通过；
- D3（真正 kill Service）受平台限制，`am stop-service` 无法停 foreground media service，
  待 root/异常进程路径；详见验收文档。
- 详见 `docs/ACCEPTANCE_V0.2.15.md`。

## v0.2.15：路径审计后的 P0/P1/P2 修复

- 审计文档：`docs/PATH_AUDIT.md`、`docs/REPAIR_PLAN.md`（BUG-01..BUG-10）。
- P0 错曲结果修复：
  - 临时源唯一化 `separate_input_<uriHash>.<ext>`；
  - repo 终态携带 `sourcePath`；Domain 丢弃不匹配的迟到终态；
  - EdgeSession source 不匹配时 `tickEdge`/`publishEdgeUi` 不发事件；
  - `Domain.start` 校验 request/current TrackKey；
  - Finished 任务按 currentTrackKey 过滤（FULL 后台完成不推当前曲 UI）。
- P1：
  - 分离开关走 Domain attach/detach + PlayerBridge；
  - PlaybackService/SeparationService onDestroy 上报 ServiceLost；
  - 旧临时源清理（>1h）。
- P2：
  - 导出加模型目录：`Music/separate/<song>/<model>/`；
  - `inferModelDirName` 从 cache 后缀推断 spleeter-2stem/spleeter-4stem/demucs。
- JVM **438/0**；真机 A（who edge 多轨）→ B（bullet）极快：B 命中自己的 cache/source；
  开关 off/on、导出模型目录通过；R8 release 构建通过。

## 已知限制 / 后续可做

- AVS3/NCM 参考解码器不入库；没有它时相关格式跳过，不能随 release 分发。
- 真实来电的音频焦点丢失没有真实通话环境验证（焦点请求/丢失逻辑已自动测）。
- 非 1× 速度的后台长播：门面 ExoPlayer 可能先 ENDED，mixer 还在收尾；前台正常。
- release 暂未开 R8；debug 测试入口已移入 `src/debug`；R8/体积优化需单独分支。
- `PlaylistStore` 的 VM 整份快照在极端时序下仍可能写回旧进度（已有锁避免数据丢失，只剩自愈式回退）。
