# 状态稳定性测试报告（v0.2.55 候选）

> 状态：**进行中**，未发 v0.2.55。  
> 目标门禁见 [STABILITY_TEST_PLAN.md](STABILITY_TEST_PLAN.md)。  
> 设备：BF00A（Android 16，arm64），volume 3/15。

## 1. 构建

- 被测 debug：`0.2.55-test (255)`，`-PtestHooks=true`，TEST_HOOKS 测试入口
- 正式基线：`0.2.54 (254)`
- 代码 HEAD：`503ce4e` + 本轮稳定性修复/测试

## 2. 自动化

| 项 | 结果 |
|---|---|
| JVM 全量 | **507/0**（含随机 reducer 2000 seeds、MP4 codec probe、损坏歌单、WAV 完整性） |
| androidTest | **1/0**（MultiTrackEngineSmokeTest：多轨/全静音/退出） |
| 5min soak | 319 ops / 3 restarts / 0 FATAL / 0 ANR / 0 player error |
| 2h soak | **进行中** |

### 本轮发现并修复

- **Stopping 时迟到 `StemsLoaded` 清空 Cancelling/pendingStart**：
  - 随机模型测试 seed=4 复现
  - 修复 `SeparationReducer.onStemsLoaded()`：Stopping 直接忽略迟到加载
  - 加回归测试

### 测试环境问题（非 App 缺陷）

- 第一次 2h soak 无效：`connectedDebugAndroidTest` 结束后 App 被卸载，`monkey` 未拉起 App；已停止，不计入结果。
- 第二次重试用 debug 重装 + READ_MEDIA_AUDIO 授权 + `file://` 曲目，确认 App 进程和命令总线存活后再跑。

## 3. 2h soak

- 目录：`stability-out/soak-2h-c/`
- 结果：待填
- 检查项：
  - FATAL / ANR
  - 播放失败（`播放失败` / `ERROR_CODE_`）
  - 波形失败（`waveform extraction failed` / `no audio track`）
  - heartbeat（多轨时）
  - 重启后是否恢复

## 4. 关键序列

待执行：

1. 冷启动→播放→暂停→seek→后台→前台→曲尾
2. LOOP_ONE→重播→LOOP_ALL→队列尾
3. 多轨→全静音→位置推进→退出
4. 多轨中切歌不双声
5. 分离完成→attach→退出→再 attach
6. 分离中切歌/取消/清缓存
7. 半截 coverage 不可导出
8. 波形提取中切歌/清缓存
9. 删除当前曲/歌单
10. revoke grant→重新选择
11. 损坏 playlists.json
12. write 失败→AudioTrack 重建→EQ 重挂
13. Service lost / force-stop
14. AV3A 明确拒绝
15. 进程重启恢复

## 5. 已知剩余问题

- C：SeparationService 启动竞态仍是专项问题
- #2 Stopping 完整 UI 复现仍不稳定性
- B1 只完成 PlaybackConnection 切口

## 6. 发布决定

- [ ] 2h soak 通过
- [ ] 15 条关键序列通过
- [ ] androidTest 扩展通过
- [ ] 无未解释 P0/P1
- [ ] 报告收口

**未全绿前不发 v0.2.55；下一版若包含 B1 架构改动则跳 v0.3.0。**
