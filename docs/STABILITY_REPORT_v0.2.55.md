# 状态稳定性测试报告（v0.2.55 候选）

> 状态：**RC 已验证；v0.2.55 未打 tag/未发布**。  
> 设备：BF00A（Android 16，arm64），音量 0/15；测试末尾设备停在锁屏，需解锁继续路由测试。  
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
| 2h soak | **7128 ops / 90 restarts / 0 FATAL / 0 ANR / 0 player error / 0 waveform error** |
| 关键序列脚本 | **5/5 PASS** |
| 分离/导出 | 2 轨、4 轨、Demucs 均 PASS |
| SAF grant | 1→0 PASS |
| 队列/repeat | PASS |
| write 故障注入 | PASS（AudioTrack 重建、EQ 重挂、heartbeat） |
| 损坏 playlists.json | PASS（保留 corrupt 副本，不覆盖） |
| Service 启动竞态 `sepdouble` | PASS（job2 Running；UI 缓冲提示；自动多轨） |

## 3. 本轮发现并修复

1. **Stopping 时迟到 `StemsLoaded` 清空 `pendingStart`**
   - 随机 reducer 测试复现，已修 + 回归测试

2. **C：SeparationService 启动竞态**
   - 第二次 Start 读到旧终态 `stopSelf` → job2 “分离服务丢失”
   - 修复：2s 启动宽限 + 新 Start 取消旧 grace timer + 宽限后核对
   - `sepdouble` 与 UI 均验证通过

3. **歌单损坏静默 0 首**
   - 发现：`PlaylistStore` 损坏时拒绝覆盖，但 UI 只显示 0 首，导入静默失败
   - 修复：歌单页 error banner + 确认重建入口；`resetCorruptToDefault()` 原子恢复
   - JVM 回归：损坏 → 备份存在 → 重建 → `corrupt=false` → 保存恢复
   - 真机：banner 显示 → 重建 → 导入 `0→1 首` 成功

## 4. Release 验证

- 升级：debug `0.2.55-test` → release `0.2.55`，安装成功，数据保留
  - 歌单仍为 1 首
  - Spleeter 2 轨模型仍显示“已安装”
  - `TestCommandService` 在 release 中不存在（`am start-service` 返回 not found）
- Clean install：release APK 全新安装成功
  - 首次导入 `who_am_i_44k.wav` → 1 首
  - 播放日志 `loadAndPlay`，无 FATAL/ANR
- 息屏 8s 后亮屏：MediaSession 仍 `PLAYING`，位置继续

## 5. 未完成/受限

- **蓝牙/USB 耳机路由、拔出/插入**：设备锁屏，无法继续 UI/人工听感验证
- **音频焦点抢占/恢复**：需要来电或第二个音频 App，当前未做
- 30–60min 多轨 + 分离 + 后台混合 soak
- release 包下分离功能（模型升级保留，但未在 release 再跑一次 edge/4-stem/Demucs）
- 分离取消的完整 UI 回退断言仍是 PARTIAL

## 6. 发布决定

**暂不打 v0.2.55 tag。** 等设备解锁后补：
1. 蓝牙/USB 路由 + 焦点
2. release 包分离 smoke
3. 30–60min 多轨/后台 soak

全绿后再 tag；下一版若包含 B1 架构改动则直接 **v0.3.0**。
