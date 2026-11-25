# 控件 —— 状态依赖 —— 状态影响 对照表

> 生成：2026-11-25，基于当前 `main`（v0.2.60）真实代码逐文件提取。
> 目的：给接手/改 UI 的人一张“点这个会改什么、它什么时候能点”的完整地图。
> 表内 `_state` / `vm.xxx()` 默认指各功能对应的 ViewModel；`Service` 指 `PlaybackService`。
> 文中所有「依赖」与「影响」均来自代码，未臆造；个别待核处以“待核”标注。

## 阅读约定

- **控件**：UI 上可操作的东西（按钮/滑块/开关/输入框/可点击项/手势）。
- **依赖的状态**：决定它是否显示、是否可点（enabled）、显示什么内容/高亮的字段或条件。
- **影响的状态**：操作后实际调用的方法，以及该方法最终改写的 State 字段或副作用。

---

## 一、播放器（PlayerScreen / DeckView / ProgressBar / PitchKnob / VerticalSlider / WaveformView）

| 控件 | 类型 | 依赖的状态 | 操作影响的状态 |
|---|---|---|---|
| 导入音频（空态） | Button | 仅当 `!hasMedia && importEnabled` | `pickAudio.launch` → 选中后 `vm.loadAndPlay(uri,name)` → 改 `_state` 文件/进度/多轨态 + `onImported` 追加歌单 |
| 导入音频（有曲态） | Button | 仅当 `importEnabled` | 同上：`vm.loadAndPlay` + `onImported` |
| 播放/暂停 | Button | 文案= `playWhenReady ? 暂停 : 播放` | `vm.togglePlayPause` → 单轨 `p.play()/pause()`；多轨 `CMD_TOGGLE` → 改 `isPlaying/playWhenReady` |
| 快退 10s | Button | 目标= `positionMs-10000`（≥0） | `vm.seekTo(...)` → 改 `_anchor`/`_progress.positionMs` + `p.seekTo` + gate |
| 快进 10s | Button | 目标= `positionMs+10000` | 同上 `vm.seekTo` |
| 循环模式切换 | Button | 图标/文案= `repeatMode.glyph/labelRes` | `vm.cycleRepeatMode` → `setRepeatMode` → 持久化 + `mediaController.repeatMode` + 改 `_state.repeatMode` |
| 分离开关（多轨） | Switch | checked=`isMultiTrack`；enabled= 分离进行中且未切多轨时禁用 | `onToggleSeparation` → `vm.toggleSeparation`：开→校验 `lastStems`→`loadMultiTrack`；关→取消 edge + `exitMultiTrack`；无结果时返回 false → 提示条 |
| 去分离页 | 可点击项 | 仅竖屏 `showGoSeparate` | `onOpenSpike()` 导航 |
| 速度竖条 | Slider | value=`speed`；范围=`speedSliderMin..Max`；1.0× 吸附；对数尺度 | `vm.setSpeed` → 改 `_state.speed` + `applyPlaybackParameters`（防抖下发） |
| 音高竖条 | Slider | value=`pitchSemitones`；范围 -12..12；0 吸附 | `vm.setPitchSemitones` → 改 `_state.pitchSemitones` + `applyPlaybackParameters` |
| 重置速度/音高 | 可点击项 | 固定显示 | `vm.resetSpeedPitch` → speed=1、pitch=0 + 下发 |
| 变调旋钮（竖屏 DJ） | 旋钮拖动 | semitones=-12..12；accumulating 决定显示值 | 松手 `vm.setPitchSemitones` → 改 `_state.pitchSemitones` |
| 进度条拖动 | Slider | duration>0 才 seek；dragging 冻结显示 | 松手 `onSeek`=`vm.seekTo` → 改 `_anchor`/`_progress` |
| 转盘内圈点击 | Canvas 手势 | 触点 < 半径 34% | `vm.togglePlayPause` |
| 转盘外环点击 | Canvas 手势 | 触点 ≥ 半径 34% | `vm.seekTo(f*dur)` → seek |
| 转盘横向滑动 | Canvas 手势 | 累计位移 >120f 触发 | 左滑 `vm.playPrev`、右滑 `vm.playNext` → 改队列/加载当前曲 |
| 波形点击/拖动 | Canvas 手势 | durationMs；波形有数据 | `vm.seekTo(dragPositionMs)`（拖动持续 seek） |

---

## 二、混音 + EQ（MixScreen / TrackMixerPanel / EqPanel）

| 控件 | 类型 | 依赖的状态 | 操作影响的状态 |
|---|---|---|---|
| 退出多轨 | FilledTonalButton | 仅 `isMultiTrack && tracks 非空` | `vm.exitMultiTrack` → `CMD_EXIT` + 清多轨态 + 重载原曲续播 |
| 音量滑块（每轨） | Slider | value=`track.volume` | `vm.setTrackVolume` → `CMD_SET_VOLUME` → 改该轨 volume + 重算 gainSnapshot → 回写 `_state.tracks[*].volume` |
| 静音按钮（每轨） | TextButton | 图标= `track.muted ? 🔇 : 🔈` | `vm.toggleMute` → `CMD_TOGGLE_MUTE` → 翻转 muted + 重算 gain → 回写 `_state.tracks[*].muted` |
| 独奏按钮（每轨） | TextButton | 图标= `track.solo ? 🎧 : 🎶` | `vm.toggleSolo` → `CMD_TOGGLE_SOLO` → 切换 soloTrackId + 重算 gain → 回写 `_state.tracks[*].solo` |
| 启用 EQ | Switch | checked=`eqEnabled`；面板仅 `eqAvailable` 时显示 | `vm.setEqEnabled` → `CMD_SET_EQ_ENABLED`（首次全零先下发默认曲线）→ 回写 `_state.eqEnabled` |
| EQ 频段滑块 | Slider | value=`levelsMb[b]`（本地脏段不跟随）；频点= `freqsHz[b]` | `vm.setEqBandLevel` → `CMD_SET_EQ_BAND`（≥100ms 限频，松手补终值）；拖动即自动开 EQ |
| 重置 EQ | FilledTonalButton | 仅 `eqAvailable` | `vm.resetEq` → `CMD_RESET_EQ` |

> 增益优先级：**独奏 > 静音 > 音量**；被独奏或静音的轨 gain=0。

---

## 三、分离（SeparationScreen / SeparationPolicyScreen）

| 控件 | 类型 | 依赖的状态 | 操作影响的状态 |
|---|---|---|---|
| 2 轨速度簇档位按钮 | Button | 仅 `twoTierEnabled && 有已装候选`；enabled=`hasSong && !busy && benchmarked`；ULTRA 加「⚡」 | `startTwoTier` → 选已装模型 → `startEdgeOption`/`startOption` → 驱动 `domainSnapshot`/`pendingCompletion` |
| 4 轨档位按钮（快速/质量） | Button | 仅 `fourTierEnabled && 已装`；enabled 同上；≥2× 加「⚡」 | `startTier` → 选模型 → `startSherpaOption`/`startNonSherpaOption` → 驱动 `domainSnapshot` |
| 去模型库下载 | Button | 仅当 2/4 轨档位全空且 `onOpenModelLibrary!=null` | `onOpenModelLibrary()` 导航 |
| 分离开关 | Switch | checked=`separationOn`；enabled=`hasSong && (on || !busy)` | `onToggleSeparation` → `vm.toggleSeparation`；开失败 → 提示条 |
| 补齐前段 | Button | 仅 `pendingBackfillMs>0`；enabled=`hasSong && !busy` | `backfillNow` → `startEdgeOption(src,0)` 续算缺口 |
| 取消分离 | Button | 仅 `busy` | `vm.cancelSeparation` → 驱动 `domainSnapshot`/`cancelledNotice` |
| 导出 stem | Button | enabled=`exportableFiles != null`（Finished 且属当前曲） | `requestExport` → `StemExporter.export`；API<29 先请求写权限 → 写 `exportMsg` |
| 清除缓存 | Button | enabled=`!separationOn && !busy` | `vm.clearCache` → 删缓存目录 → 写 `cacheMsg` + `onCacheCleared` |
| 返回 | TextButton | 固定 | `onBack()` |
| 2 轨速度簇启用开关 | Switch | checked=`twoTierEnabled`；enabled= 有已装候选 | `repo.setTwoTierEnabled` → 写偏好 → 影响分离页该档显示 |
| 2 轨簇候选模型 chip | FilterChip | selected=`selected==id`；仅开关开且有候选 | 切换模型 `repo.setTwoTierModelId` → 影响 startTwoTier 用哪个模型 |
| 4 轨档位启用开关 | Switch | checked=`fourTierEnabled`；副文案= 模型名+未安装标记 | `repo.setFourTierEnabled` → 影响分离页 4 轨档显示 |
| 边分离边播开关 | Switch | checked=`edgeSeparationEnabled`（默认 true） | `repo.edgeSeparationEnabled` → 影响极快档是否从当前位置起 |

---

## 四、歌单（PlaylistScreen）

| 控件 | 类型 | 依赖的状态 | 操作影响的状态 |
|---|---|---|---|
| 新列表名输入框 | OutlinedTextField | value=`newName` | 改 `newName` 本地状态 |
| 新建 | Button | `newName` 非空才生效 | `vm.createList` → 改 `_lists` + `persist()` 落盘；清空 `newName` |
| 选择文件夹导入 | OutlinedButton | 固定 | `pickFolder.launch` → 授权后 `vm.importFolderAsync` → 新增文件夹歌单 → 改 `_lists` |
| 导入单曲/多选 | OutlinedButton | enabled=`!selectedIsFolder` | `pickFiles.launch` → `vm.importFilesAsync` → 追加到选中歌单 → 改 `_lists` |
| 损坏横幅「重建空歌单」 | TextButton | 仅 `corrupt` | `showCorruptReset=true` |
| 重建确认「重建」 | TextButton | 弹窗 `showCorruptReset` | `vm.recoverCorruptPlaylist` → 原子重建 → 改 `_lists`/`_corrupt` |
| 重建确认「取消」 | TextButton | 弹窗 | `showCorruptReset=false` |
| 歌单卡片头（点信息区） | 可点击项 | 高亮=`isSelected` | `vm.selectList` + `onSelectPlaylist` → 改 `selectedListId` + 播放该歌单 |
| 默认歌单「清空」 | TextButton | 仅 `p.isDefault` | `vm.clearList` + `onPlaylistCleared` → 改 `_lists`（清空但保留选中） |
| 非默认「删列表」 | TextButton | 仅 `!p.isDefault` | `vm.deleteList` + `onPlaylistRemoved` → 改 `_lists` + 释放 grant |
| 展开/收起箭头 | TextButton | `expandedId==p.id ? ▴ : ▾` | 切换 `expandedId` |
| 曲目行 | 可点击项 | 高亮=`t.uri==currentUri` | `onPlayTrack(p.id, idx)` → 以整个歌单为队列播放 |
| 重新授权 | TextButton | 仅 `t.uri in brokenTrackUris` | `reauthPicker.launch` → `vm.replaceTrackUri` → 改 `_lists` 中该曲 uri |
| 排序上移/下移 | TextButton | enabled=`idx>0` / `idx<size-1`；仅文件歌单 | `vm.moveTrack` + `onTrackMoved` → 改 `_lists` 顺序 |
| 移除曲目 | TextButton | 仅文件歌单 | `vm.removeTrack` + `onTrackRemoved` → 改 `_lists` + 释放未用 grant |
| 再加载 N 首 | TextButton | `shown < tracks.size` | `trackLimit += PAGE` → 展开更多曲目 |
| 刷新文件夹 | TextButton | 仅 `folderUri != null` | `vm.rescanFolderAsync` → 改 `_lists` |

---

## 五、设置 + 模型库（SettingsScreen / ModelLibraryScreen / ModelManagerSection）

| 控件 | 类型 | 依赖的状态 | 操作影响的状态 |
|---|---|---|---|
| 模型库入口 | 可点击项 | 副标题= 已装/总数/体积 | `page=MODELS` |
| 短检测 / 长检测 | Button | enabled=`!benchmarking` | `vm.invalidateAndRun` → `runBenchmark` → 改 `_state.benchmarking/benchmarked` 等 |
| 继续检测 | TextButton | 仅 `!benchmarking && installedPending>0` | `vm.runBenchmark(resume=true)` |
| 分离策略入口 | 可点击项 | 副标题= 启用档数 | `page=POLICY` |
| NCM 导入 / 解码缓存入口 | 可点击项 | 仅 `WITH_NCM=true` | 切到对应二级页 |
| PCM 缓存入口 | 可点击项 | 默认传入非 null | `page=PCM_CACHE` |
| 转盘一圈时长 | Slider | value=`deckSecondsPerTurn`（9..36） | `repo.deckSecondsPerTurn` → 影响转盘转速 |
| 变速范围 | Slider | value=`speedRangeX`（1.2..4.0） | `repo.speedRangeX` → 影响速度滑块区间 [1/X, X] |
| 边分离边播开关 | Switch | checked=`edgeSeparationEnabled` | `repo.edgeSeparationEnabled` |
| 强制检测弹窗「立即检测」 | TextButton | 仅 `!benchmarked` | `vm.markBenchmarkPrompted` + `runBenchmark` |
| PCM 缓存「刷新」 | OutlinedButton | 固定 | `store.cacheBytes/cacheFileCount` → 改占用显示 |
| PCM 缓存「清理未使用」 | OutlinedButton | enabled=`files>0` | `showClearConfirm=true` → 确认后 `store.clearUnpinned` |
| 解码缓存上限档位 | FilterChip | selected=`gb==v`（1..4） | `repo.decodeCacheGb` |
| 预解码数量档位 | FilterChip | selected=`pre==v`（0..5，0=关） | `repo.preDecodeCount` |
| 解码缓存「清除」 | OutlinedButton | enabled=`fileCount>0` | 确认后 `Avs3PcmCache.clear` |
| 下载源档位 | FilterChip | selected=`pref==p`（AUTO/HF/HF_MIRROR） | `repo.modelSourcePreference` + 清探测缓存 |
| GitHub 代理输入框 | TextField | value=`downloadProxy` | 仅改本地；「保存」才 `repo.downloadProxy` |
| 代理「保存」 | TextButton | 仅 `value != 已存值` | `repo.downloadProxy` → 影响 UVR 下载前缀替换 |
| 模型体积节头 | 可点击项 | 展开态=`installedBuckets/manualOpen/manualClosed` | 切换展开/折叠 |
| 模型「下载/重新下载」 | TextButton | 文案按 `installed || Done` | `licenseFor=pack` → 弹许可确认 |
| 模型「取消下载」 | TextButton | 仅 `Downloading` | `downloader.cancel(pack)` |
| 模型「删除」 | TextButton | 仅 `shownInstalled` | `deleteFor=pack` → 确认后 `downloader.delete` + 刷新已装 |
| 许可确认「同意下载」 | TextButton | 弹窗 | `downloader.start(pack,pref)` → 下载 |
| 删除确认「确认删除」 | TextButton | 弹窗 | `downloader.delete` → 刷新 `installedIds` |

---

## 六、状态真源（谁持有、谁写、谁读）

| 状态 | 持有者 | 写者 | 读者 | 派生关系 |
|---|---|---|---|---|
| `PlayerState`（`_state`→`state`） | PlayerViewModel | doLoad/loadAndPlay/loadMultiTrack/applyTracksSnapshot/applyEqSnapshot/bareExitMultiTrack/clearCurrent/updatePlaying/各 listener/setSpeed/setPitch/resetSpeedPitch/setRepeatMode/setEqEnabled/collect 波形-封面-edgeUi 等 | PlayerScreen/MixScreen/AppShell/MainActivity | `isMultiTrack`/`eqAvailable`/`eqEnabled` 由 `SessionStateMachine` 经 `withDerived` 派生；`separationEdge` 由 `SeparationDomain.edgeUi` 派生；`avs3Decode` 由 session 同步 |
| `progressFlow`（Progress） | PlayerViewModel | 100ms 轮询 / onPositionDiscontinuity / seekTo / doLoad / clearCurrent | ProgressBar/DeckView/PlayerScreen/AppShell | 独立高频流；多轨时由 mixPosMs+本地插值 |
| `anchorFlow`（UiClock?） | PlayerViewModel | 进度轮询 / seekTo / discontinuity / clearCurrent | ProgressBar/DeckView/SmoothPosition | 用本地单调时钟外推平滑时间轴 |
| `brokenTracks` | PlayerViewModel | handleUnplayableTrack(+uri) / READY(-uri) | AppShell（歌单标记） | — |
| `userMessage` | PlayerViewModel | loadAndPlay(AVS3 拒绝) / consume | MainActivity(Snackbar) | 一次性提示 |
| `currentListIdFlow` | PlayerViewModel | playQueue / loadAndPlay(clearQueue) / clearCurrent / restore | AppShell（同步选中态） | null=非歌单来源不记进度 |
| `_stemWaveforms` | PlayerViewModel（私有） | extractStemWaveforms / cancelStemWaveforms / edge 包络循环 | 镜像到 `state.stemWaveforms`→DeckView 外环 | 私有中间流 |
| PlaybackService 播放/多轨真源 | PlaybackService（plain 字段，非 StateFlow） | handleLoadMultiTrack/Stream/Exit/SetVolume/ToggleMute/ToggleSolo/SetSpeedPitch/SetEq*/progressRunnable/reconcileFacadeToMixer | PlayerViewModel.onExtrasChanged（经 sessionExtras） | `inMultiTrack/lastSpeed/lastPitchRate/mtOriginalUri/title/singlePlayer/multiTrack/eqController`；经 `pushTracksSnapshot`/`progressRunnable` 写 sessionExtras 回传 |
| SpikeViewModel `state`（sealed） | SpikeViewModel | importAudio/loadLocal/loadExternal/applyDomainSnapshot/startSeparation/cancelSeparation/reset | 无直接订阅（已被 domainSnapshot 取代） | 页面本地态；Done 等转发自 Domain |
| `domainSnapshot` | SpikeViewModel（只读代理） | 只读 | SeparationScreen | 直接引用 `SeparationDomain.snapshot` |
| `pendingCompletion` | SpikeViewModel | applyDomainSnapshot(FULL 完成) / consume | SeparationScreen（→onPlayStems） | 一次性事件，按 jobId 去重 |
| `cancelledNotice` | SpikeViewModel | startDomain(false)/cancel(true)/reset | SeparationScreen | 取消后短提示 |
| `lastEdgeDone` | SpikeViewModel | applyDomainSnapshot(EDGE)/5s flash/start/clear/reset | SeparationScreen | 5 秒后消失 |
| `snapshot`（SeparationSnapshot） | SeparationDomain（经 Controller） | 所有 user/domain/external 事件 → reducer | SpikeViewModel/AppShell/SeparationScreen | 分离领域单一真源 |
| `edgeUi`（SeparationEdgeUi） | SeparationDomain | publishEdgeUi()（200ms tick） | PlayerViewModel（→`separationEdge`） | 每 200ms 由 EdgeSession+snapshot 重算，不独立存储 |
| `notices`（SharedFlow） | SeparationDomain | execute()/start() | AppShell（Snackbar） | 一次性提示通道 |
| `lists` | PlaylistViewModel | init/reload/create/delete/clear/remove/replace/add/append/move/import 等 | PlaylistScreen | 内存缓存，真源在磁盘 JSON |
| `corrupt` | PlaylistViewModel | init/reload/recover | PlaylistScreen | 损坏期间拒写 |
| `saveError` | PlaylistViewModel | persist 失败 / init 清 | PlaylistScreen | 最近落盘失败 |
| `selectedListId` | PlaylistViewModel | selectList / ensureValidSelection | PlaylistScreen | 恒存在，删除回落默认 |
| `selectedList` | PlaylistViewModel（派生 getter） | 派生 | PlaylistScreen/AppShell | `lists.firstOrNull{id==selectedListId}` |
| `state`（UiState） | SettingsViewModel | refresh/buildState / runBenchmark / setDefaultEngine / setEqEnabled | SettingsScreen | 每次由 benchmark 缓存+repo+ModelRegistry 一次性重算，非增量派生 |

---

## 七、跨功能的关键联动（几个容易踩的耦合）

1. **分离开关（播放页）↔ 分离开关（分离页）↔ 多轨播放**：三者都收敛到 `vm.toggleSeparation` → `_state.isMultiTrack`。播放页和分离页的 Switch 是同一个真源的镜像。
2. **`isMultiTrack` 的三处镜像**：`PlayerViewModel._state`（UI）、`PlaybackService.inMultiTrack`（真源）、`SessionStateMachine`（派生注入）。改多轨态必须走 `session.syncMode`，不能只改 UI 布尔。
3. **波形/封面/edgeUi 是异步 collect 回写** `PlayerState` 的：切歌后旧波形/封面可能迟到，靠 token/uri 校验丢弃（见 `WaveformExtractor`/`CoverExtractor`）。
4. **播放进度双流**：`progressFlow`（高频，位置真源）+ `anchorFlow`（平滑锚点）。UI 只读这两者，不读 `PlayerState.positionMs`（已无 writer）。
5. **EQ 状态两端**：`SessionStateMachine.eqEnabled`（真源）↔ `EqController`（Service 侧硬件）+ `PlayerState.eqEnabled/eqAvailable/eqLevelsMb`（UI 镜像），经 `session.syncEq` 同步。
6. **SAF 授权生命周期**：授权在 UI（`pickFiles`/`pickFolder` 回调），释放在 `PlaylistViewModel`（删除/移除/清空时）。删除当前曲 → 释放 grant → 该曲进 `brokenTracks`。
7. **模型“已装”判定**：`ModelCatalog.installed(filesDir)`（磁盘文件大小/SHA）↔ `ModelDownloader.State.Done`（本次下载）。下载完成要同时刷新磁盘判定才显示已装。

---

## 八、未覆盖 / 待核

- `player/WaveformView.kt` 的「波形点击」组件：提取时发现该文件当前在 `PlayerScreen` 主调用链中未见直接引用，标记**待核**（可能被 `DeckView` 或旧入口使用）。
- 横竖屏布局下同一控件的 `enabled`/可见性条件可能不同（速度/音高/重置在横屏左栏与竖屏重复定义），表内已分别列出主要分支，未穷举所有 `isLandscape` 分支。
- `AppShell.kt` 的导航/底部栏（播放器/歌单/分离/混音/设置）是页面级入口，未纳入“控件”表（它是 tab 切换，非功能控件）；其 `current`（rememberSaveable）、`switchedSessionKey`、`autoStartSeparate`、`openModelLibToken` 为导航/联动状态。
- `SettingsScreen` 内 `EdgeSeparationSetting` 与 `SeparationPolicyScreen` 的边播开关是同一偏好的两处入口。
