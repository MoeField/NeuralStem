# 审计整改状态与计划

> 基线：**v0.3.0 (300)**，tag `v0.3.0`，HEAD 以 `git log` 为准。  
> 本文是**整改看板**；不变量见 [ARCHITECTURE.md](ARCHITECTURE.md)，发布历史见 [STEP_HISTORY.md](STEP_HISTORY.md)，
> 结构重构计划见 [V0.3.0_PLAN.md](V0.3.0_PLAN.md)。

## 0. TL;DR（v0.3.0）

- **v0.2.60 REVIEW 的全部发现已闭环**：2 条 P1（P1-A 缓存命中导出、P1-B tracks 竞态）+ 20 条 P2，
  其中 P2-16 复核后**有意保留**（NCM 构建需要 READ），P2-12 为注释明示的固有限制。
- **NEED-CONFIRM 5 项全部有结论**：1/2 关闭、3 发现并修复（SeparationService 常驻前台）、4 确认、5 真机验证。
- 普通 JVM **519/0**（T1/T2 补充测试后）；`lintVitalRelease`、`with_ncm` 编译、APK 无 AVS3 断言均在 CI。
- 当前稳定基线 **v0.3.0**；下一阶段（v0.3.1+）只做**结构拆分**，见 §6。

### 验证等级

| 级别 | 含义 |
|---|---|
| V0 | 代码修复，未做故障注入/专项测试 |
| V1 | JVM 纯逻辑测试覆盖 |
| V2 | 真机 smoke 或 CI 门禁实际跑过 |
| V3 | 故障注入 + 回归测试 |

---

## 1. v0.2.60 REVIEW 闭环看板（当前账）

来源：`.review/REVIEW_v0.2.60.md`，逐条回应：`.review/REVIEW_v0.2.60_RESPONSE.md`。

### P1

| # | 问题 | 状态 | 版本 | 验证 |
|---|---|---|---|---|
| P1-A | 缓存命中 → `complete=false` → 导出阻断 | 已修 | 0.2.61 | `Done/Done4` 携带真实 `totalFrames`；缓存从 WAV 头读回；真机命中后 `loadMultiTrack` 自动触发 + JVM 帧数测试 |
| P1-B | 多轨 `tracks` 跨线程双写者 | 已修 | 0.2.61 | `@Volatile List<Track>` 整体替换 + 三条命令 `multiLoadPending` 门控；真机 30 轮高频 `mtvol` 无 CME |

### P2

| # | 问题 | 状态 | 版本 |
|---|---|---|---|
| 1 | `mixer` 非 volatile | 已修 | 0.2.61 |
| 2 | 全静音首源 EOF 永久空转 | 已修 | 0.2.62 |
| 3 | 门面提前 ENDED 不对齐 | 已修 | 0.2.62 |
| 4 | `listeners` 死代码 | 已修 | 0.2.66 |
| 5 | `pushTracksSnapshot` 抹 `KEY_MIX_*` | 已修 | 0.2.62 |
| 6 | EDGE 无停滞超时 | 已修 | 0.2.62 |
| 7 | Decoding 超时不 cancel | 已修 | 0.2.62 |
| 8 | Separating 超时单发不失败 | 已修 | 0.2.62 |
| 9 | attach 侧不校验 `complete` | 已修 | 0.2.63（FULL/手动 attach 校验；EDGE 边播保留部分覆盖） |
| 10 | `MakeResult` 未逐声道判空 | 已修 | 0.2.63 |
| 11 | `ensure_loaded` 非线程安全 | 已修 | 0.2.63（`pthread_once`） |
| 12 | AVS3 输出越界校验“事后” | **保留** | 注释明示固有限制；`lenOut=outCap` 作容量入参已加 |
| 13 | release 里 dispatch 无守卫 | 已修 | 0.2.61（DEBUG/TEST_HOOKS 守卫） |
| 14 | devtools 类在 `src/main` 与文档矛盾 | 已修文档 | 0.2.63（DEVELOP.md 措辞） |
| 15 | 备份漏 `avs3-pcm-cache`/`NCM` | 已修 | 0.2.64（含 `BackupRulesConsistencyTest` 防再犯） |
| 16 | 冗余 `READ_MEDIA_AUDIO`/`READ_EXTERNAL_STORAGE` | **保留** | 复核：NCM 读 `/sdcard/Music/NCM` 需要；公开版不 request 不弹窗 |
| 17 | `export(original)` 死参数 | 已修 | 0.2.63（删除） |
| 18 | `deleteExisting` 未限 owner | 已修 | 0.2.63（projection 过滤，避免 selection 误删） |
| 19 | 日志脱敏残留 4 处 | 已修 | 0.2.63（`Uri.redacted()`） |
| 20 | 文件夹导入固定 READ\|WRITE | 已修 | 0.2.63（收窄为 READ） |

### NEED-CONFIRM

| # | 项 | 结论 |
|---|---|---|
| 1 | `coverage.totalFrames` 缓存命中=0 | ✅ 确认为 P1-A，已修 |
| 2 | 孤儿 worker 并发写同目录 | ✅ 已关闭（Mutex 串行化，v0.2.59） |
| 3 | dataSync 前台服务时长限制 | ✅ **发现真实 bug**：终态后服务常驻前台；v0.2.65 修复 + 真机验证（0.5s 内 stopSelf） |
| 4 | devtools 在 main、release 可达性 | ✅ 确认；已加 dispatch 守卫 |
| 5 | PcmStore 租约延迟误删 | ✅ 真机验证：清理未使用 299MB/5→29MB/1，当前曲 pin 保留 |

### P1-14（I1–I3 守护测试）

- v0.3.0：抽 `AudioSink` + `FakeAudioSink`，补 **I2**（write 失败→重建→继续）、**I3**（曲尾 ended→重播重启）；JVM 514/0。
- 剩余 I1（渲染线程异常不退出）仍依赖真机 heartbeat，后续可继续用 fake sink 覆盖。

---

## 2. 历史审计（v0.2.42–v0.2.59，已收口，保留可追溯）

> 以下 P0/P1 来自 v0.2.42–0.2.49 的旧审计，**全部已修**；与 §1 是两套账，不要混读。

### P0

| # | 问题 | 版本 | 验证 |
|---|---|---|---|
| 1 | AudioTrack 重建后没 `play()`、EQ 挂旧 session | 0.2.42/48 | V3（注入 write 失败） |
| 2 | `Stopping` 永久卡死 | 0.2.43/48/49/55 | V3（watchdog + `sepdouble`） |
| 3 | 导出未检查 `coverage.complete` | 0.2.42 | V1 |
| 4 | release 自动化 Intent 无 DEBUG 守卫 | 0.2.42 | V2 |
| 5 | 损坏歌单被进度写覆盖 | 0.2.42/48 | V1 |
| 6 | 默认第三方模型代理 | 0.2.44/47 | V2 |

### P1（12 条）

#7 PcmStore 窗口、#8 缓存/WAV 长度、#9 输出形状、#10 `isPlaying` 多写者、#11 tracks URI、#12 主线程 join、
#13 SAF grant、#14 全轨静音进度、#15 CI lintVital、#16 CI NCM/APK 断言、#17 下载生命周期、#18 BackHandler —— **均已在 v0.2.41–0.2.49 修复**。

---

## 3. 仍开放（非阻塞，排入 v0.3.x）

- **God class**：`PlaybackService` 838 行（Step 1 已抽 `EqHost`，原 896）、`PlayerViewModel` 2529 行、`MainActivity` 1944 行、`SeparationRepository` 1420 行、`SeparationScreen` 881 行。
  计划见 [V0.3.0_PLAN.md](V0.3.0_PLAN.md)：v0.3.0 已完成 AudioSink；v0.3.1+ 拆命令处理层 → PlayerViewModel。
- **P2-12**：AVS3 输出越界校验仍是“事后”的（第三方 decode 接口无容量参数）。
- **仪器测试缺口**：`SeparationDomain`/SAF 的 Android 路径没有 CI 仪器测试。
- **发布配置未终验**：`largeHeap=true`、`useLegacyPackaging=true`、sherpa `.so` 的 16KB 对齐。
- **文档同步**：本次已补 REVIEW 看板与索引；后续每次版本仍须同步本文 + ARCHITECTURE + STEP_HISTORY。

---

## 4. 每版发布门禁（v0.3.0 起）

```text
JVM 全绿（514+）
→ lintVitalRelease
→ TAG 构建正式签名
→ APK versionName/versionCode/证书校验
→ APK 无 AVS3/VMF
→ 真机 smoke（播放/分离/多轨/Back/导出）
→ F-Droid 发布 + index 校验
```

## 5. 风险

| 风险 | 处理 |
|---|---|
| 结构拆分引入音频回归 | 先补 fake/不变量测试，再动核心；每步可 revert |
| 仪器测试依赖设备 | 先本地 AndroidTest，不阻塞 JVM CI；设备测试清单化 |
| 文档再次过期 | 每次改不变量/版本同步更新本文、ARCHITECTURE、STEP_HISTORY、docs/README |
