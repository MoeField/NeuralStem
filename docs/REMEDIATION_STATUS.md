# 审计整改状态与计划

> 基线：v0.2.48 (248)，tag `v0.2.48`，HEAD 以 `git log` 为准。  
> 本文是**整改看板**；不变量见 [ARCHITECTURE.md](ARCHITECTURE.md)，发布历史见 [STEP_HISTORY.md](STEP_HISTORY.md)。

## 0. TL;DR

- 6 条 P0 已全部有代码修复并进入正式版本；其中 #3/#4/#14 有自动化或真机验证，#1/#2/#5/#6 仍是“代码修复、缺故障注入”。
- 12 条 P1 中：**12 条已修**（#12/#13 有代码修复与专项验证；#2 仍是代码+纯函数验证，未复现到完整 UI Stopping）。
- 当前不宜再混版做 UI/架构重构；下一步按 **#9 → #17 → 验证补齐 → P2** 四阶段推进。
- 普通 JVM **503/0**；`with_ncm` **527/0**；`lintVitalRelease`、NCM 编译、APK 无 AVS3 断言均已进 CI。

### 验证等级

| 级别 | 含义 |
|---|---|
| V0 | 代码修复，未做故障注入/专项测试 |
| V1 | JVM 纯逻辑测试覆盖 |
| V2 | 真机 smoke 或 CI 门禁实际跑过 |
| V3 | 故障注入 + 回归测试（当前无 P0 达到 V3） |

## 1. P0 状态

| # | 问题 | 状态 | 版本 | 验证 | 缺口 |
|---|---|---|---|---|---|
| 1 | AudioTrack 重建后没 `play()`、EQ 挂旧 session | 已修 | 0.2.42/48 | V3 | 已注入 write 失败：新 session/EQ/heartbeat 均验证 |
| 2 | `Stopping` 永久卡死（activeJobId 提前清空、无 watchdog） | 已修 | 0.2.43/48 | V1 | watchdog 纯函数测试；完整 UI 二次 Start 未复现 |
| 3 | 导出未检查 `coverage.complete` | 已修 | 0.2.42 | V1 | 有 guard 单测；未做半截结果导出真机 |
| 4 | release 自动化 Intent 无 DEBUG 守卫 | 已修 | 0.2.42 | V2 | release 包已验证忽略 `auto_play` |
| 5 | 损坏歌单被下一次进度写覆盖 | 已修 | 0.2.42/48 | V1 | 损坏 JSON fixture 已测 |
| 6 | 默认第三方模型代理 | 已修 | 0.2.44 | V2 | 默认直连；UVR 直连下载/Range 真机验证 |

## 2. P1 状态

| # | 问题 | 状态 | 版本 | 验证 | 说明 |
|---|---|---|---|---|---|
| 7 | PcmStore 只信 length、clear/Ready 窗口 | 已修 | 0.2.43 | V1 | WAV 完整性 + activeTemps；已有 PcmStore 测试 |
| 8 | 分离缓存/WAV 长度未校验、close 无 sync | 已修 | 0.2.43 | V1 | `isCompleteWav` + `fd.sync()`；截断 WAV 测试 |
| 9 | demucs/spleeter4 输出形状未校验 | 已修 | 0.2.46 | V1 | C 形状/dtype + Kotlin 长度断言；需真机三后端 smoke |
| 10 | `isPlaying` 多写者 | 已修 | 0.2.43 | V1 | `PlayingSource/updatePlaying`；纯函数测试 |
| 11 | tracks URI 丢失 | 已修 | 0.2.42/48 | V1 | `parseTracksJson` 单测保留 uri |
| 12 | 主线程 join 卡顿 | 已修 | 0.2.41/48 | V1 | close 主线程 50ms + 后台 reaper；load 失败不替换数据源 |
| 13 | SAF grant 泄漏/失效曲 | 已修 | 0.2.43/48 | V1 | 启动孤儿 grant 清理 + 重新选择入口 |
| 14 | 全轨静音进度冻结 | 已修 | 0.2.42 | V2 | 两轨全 mute 后位置仍走 |
| 15 | CI 不跑 lintVital | 已修 | 0.2.44 | V2 | `lintVitalRelease` 进 CI |
| 16 | CI 不编译 NCM/不断言 APK | 已修 | 0.2.44 | V2 | `with_ncm` 测试 + APK 无 AVS3/VMF |
| 17 | 下载任务生命周期/续传 | 已修 | 0.2.47 | V2 | 真机：离页不中断、取消立即生效、1,266,623 B Range 续传 |
| 18 | 无 BackHandler | 已修 | 0.2.43 | V2 | 歌单页返回回播放器 |

## 3. P2 / 文档债

已确认但仍开放：

- `MAINTENANCE_AUDIT.md` 的 471 条是**审计时快照**，不是当前值；本文与 DEVELOP 为准。
- README 的“2 轨不支持导出”已过期：代码对 2/4 轨统一走 `exportableFiles`，前提是 `coverage.complete`。
- `BackfillCostGateTest` 仍复刻生产公式，存在假绿风险；应改为调用生产纯函数。
- `Spleeter4Separator` 的静态线程池仍无 `shutdown()`。
- `PlayerViewModel`/`MainActivity` 仍是大文件；`AppShell` 多分支；C2 工程卫生未做。
- 资源压缩/16KB/largeHeap 等发布配置未逐项验证。
- `SeparationDomain`/SAF 等 Android 路径没有 CI 仪器测试。

## 4. 参考项目结论（只读调研）

| 项目 | 可用结论 | 许可证/边界 |
|---|---|---|
| Rubber Ring | **不是 #1 的修法来源**（没有 AudioTrack 重建路径，write 失败只 break）；可借单写者、实写帧推进、0/负错误分流 | GPL-3.0-only；只读思路，不抄代码 |
| Auxio | 播放状态单一 owner、Session 做镜像；但快照无测试、Views 非 Compose | GPL-3.0；只读架构 |
| MonsterMusic | 对 #1 零帮助；#9 只借“先校验容量/形状、失败带码返回”的方法论 | Apache-2.0；可参考结构，注意其自身防护很薄 |
| Music | public 版无网络/存储权限属实；NCM 可走 Shizuku + app 目录，后续可移除 READ 权限 | GPL-3.0-only；只读思路 |

**没有同类项目可抄端侧分离引擎**；分离状态机/缓存/模型输出校验只能靠本项目不变量和测试。

## 5. 计划

### Phase A — v0.2.46：JNI 输出形状校验（#9，已完成代码）

范围：

1. `demucs_estimator.c`：`GetTensorTypeAndShape` + 元素数 + 逐维 shape + dtype 校验；不符释放后返回 NULL。
2. `spleeter4_estimator.c`：4 维 shape/元素数校验，不只比较总元素。
3. Kotlin：`DemucsEstimator`/`Spleeter4Estimator` 对返回数组长度做断言；失败转可定位异常/空结果。
4. 测试：Kotlin 纯函数/尺寸测试；真机跑 2 轨、4 轨、demucs 各一首短音频；故意构造错误长度（若可注入）验证不越界。

退出标准：错误输出不会 OOB/崩溃；正常三后端结果不变；CI 绿。

### Phase B — v0.2.47：下载任务生命周期与续传（#17，已完成代码）

范围：

1. 下载任务上提为 app 级 `ModelDownloadManager`（或 ViewModel + 前台服务），按 model 维护 job map。
2. `.part` + HTTP Range 续传；取消 A 不影响 B；离开页面不丢任务。
3. 失败/校验失败清理策略；与现有 SHA-256 校验衔接。
4. JVM 测试：用本地 fake HTTP server 测 Range/取消/并发；真机弱网 smoke。

退出标准：页面切换/返回不中断下载；取消单个任务不影响其他；断点续传可用；无重复下载。

### Phase C — v0.2.48：Android 验证补齐

- #2：debug 测试命令触发二次 Start，确认 Stopping 2s 内恢复。
- #10：多轨播放/暂停/通知/切歌，位置与播放态一致。
- #13：导入-删除-再导入，检查 persistable grant 数量；失效后重新授权。
- #5：损坏 playlists.json fixture，确认不覆盖。
- #11：VM 重建后 lastStems 恢复。
- 先在本机/CI 设备跑；暂不要求模拟器全覆盖。

### Phase D — v0.2.49+：P2/清理

- #12 主线程 join 改异步回收。
- #13 授权重定位/启动孤儿 grant 清理。
- 损坏歌单恢复 UI。
- `BackfillCostGateTest` 调生产函数；Spleeter4 线程池 shutdown。
- C2：`dispatchTestCommand` 下沉 debug、i18n、`PlayerState` 不可变数组。
- 大重构（PlayerViewModel/AppShell）单独排期，不和 P0/P1 混版。

## 6. 每版发布门禁

```text
JVM 全绿（普通 489+；with_ncm 513+）
→ lintVitalRelease
→ TAG 构建正式签名
→ APK versionName/versionCode/证书校验
→ APK 无 AVS3/VMF
→ 真机 smoke（播放/分离/多轨/Back）
→ F-Droid 发布 + index 校验
```

## 7. 风险

| 风险 | 处理 |
|---|---|
| #9 改 C 指针/形状逻辑引入崩溃 | 小步、所有 fail path 统一 release/return；真机三后端跑 |
| #17 改动下载器影响模型安装 | 保留旧路径开关；先 fake server 测试，再真机；单独版本 |
| 仪器测试依赖设备 | 先本地 AndroidTest，不阻塞 JVM CI；设备测试清单化 |
| 文档再次过期 | 每次改不变量/版本同步更新本文、ARCHITECTURE、STEP_HISTORY |
