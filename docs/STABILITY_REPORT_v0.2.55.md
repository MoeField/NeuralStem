# 状态稳定性测试报告（v0.2.55）

> 状态：**v0.2.55 已发布；CI + F-Droid 已验证**。  
> 设备：BF00A（Android 16，arm64），音量 0/15。  
> 计划：[STABILITY_TEST_PLAN.md](STABILITY_TEST_PLAN.md)。

## 1. 构建

- 正式基线：`0.2.54 (254)`
- RC：`0.2.55 (255)`，release，arm64 / universal / x86_64
- debug 测试包：`0.2.55-test (255)`，`-PtestHooks=true`
- RC 已过 `assembleRelease` + `lintVitalRelease`
- 签名 SHA-256：`85a5c9ebbfc867d01ccdac83a49411aad17c6fb7c42fef9b7d3a2e9b861e0d6e`

## 2. 自动化结果

| 项 | 结果 |
|---|---|
| JVM 全量 | **508/0** |
| `MultiTrackEngineSmokeTest` | 1/0 |
| `Av3aGuardTest` | 1/0 |
| 单轨 2h soak | 7128 ops / 90 restarts / 0 FATAL / 0 ANR / 0 player error |
| 多轨 10min soak | 552 ops / 4 restarts / 0 FATAL / 0 ANR / 0 player error |
| 多轨修复后 2min soak | 112 ops / `max_unmuted_started=1` / 0 error |
| 关键序列脚本 | 5/5 PASS |
| 分离/导出 | 2 轨、4 轨、Demucs 均 PASS |
| SAF grant | 1→0 PASS |
| 队列/repeat | PASS |
| write 故障注入 | PASS |

## 3. 本轮发现并修复

1. **Stopping 时迟到 `StemsLoaded` 清空 `pendingStart`**
   - 随机 reducer 测试复现，已修 + 回归测试

2. **C：SeparationService 启动竞态**
   - 第二次 Start 读到旧终态 `stopSelf` → job2 “分离服务丢失”
   - 修复：2s 启动宽限 + 新 Start 取消旧 grace timer + 宽限后核对
   - `sepdouble` 与 UI 均验证通过

3. **歌单损坏静默 0 首**
   - 损坏时 `PlaylistStore` 拒绝覆盖，但 UI 只显示 0 首
   - 修复：错误 banner + 确认重建；`resetCorruptToDefault()` 原子恢复
   - 真机：banner → 重建 → 导入 `0→1 首`

4. **多轨退出瞬态双声**
   - 快速 `exitsmt→inmt` 采样到 **2 个 `mutedState:none` 的 started AudioTrack**
   - 根因：`handleExit()` 关闭 mixer 后立即把 facade 音量恢复 1，但原曲 `doLoad` 尚未完成
   - 修复：退出时先 `pause()` 门面，再由后续 `doLoad` 拉起原曲
   - 修复后：10 轮快速 exit/inmt 与 2min soak 均 `max_unmuted_started=1`

## 4. Release 验证

- 最终 RC 重打并 clean install 成功
- 首次导入 `who_am_i_44k.wav` → 1 首
- 播放：`loadAndPlay`，MediaSession `PLAYING`
- release 中 `TestCommandService` 不存在（`am start-service` → not found）
- 升级（修复前构建）：debug→release 数据保留，歌单 1 首、Spleeter2 模型仍已安装
- 息屏 8s 后亮屏：MediaSession 仍 `PLAYING`

## 5. 未覆盖/受限

- 蓝牙/USB 耳机插拔路由：无实体设备
- 音频焦点抢占/恢复：没有可控第二音频源，未形成可靠断言
- release 包下分离全流程（模型在 clean install 后需重新下载）
- 30–60min 修复后多轨 soak（已做 2min + 10min 修复前）

## 6. 发布决定

已 tag 并发布 **v0.2.55**：CI success，F-Droid index 已确认 0.2.55 (255)。  
下一版若包含 B1 架构改动，直接 **v0.3.0**。
