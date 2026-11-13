# v0.2.15 验收记录

> 分支：`test/v0.2.15-acceptance`  
> 设备：`TLF2609A004328`，已安装 v0.2.15（versionCode 35）  
> 目标：验证 P0/P1/P2 修复，尤其是 A→B 错曲结果、开关、导出。  
> 状态：**部分完成**；未跑完的项在 §4 明确列出。

---

## 1. 自动化基线

| 项 | 结果 |
|---|---|
| JVM 全量 | **443/0** |
| 新增纯函数测试 | `CurrentTrackBridgeNameTest` 3、`SeparationGuardsTest` 5、`StemExportNamingTest` 13 |
| R8 release 构建 | 通过 |
| 签名 | `85a5c9eb…` |
| 无 UWA `.so` / debug 组件 | 通过 |
| 设备安装 | v0.2.15，数据保留 |

自动化覆盖的新点：

- 临时源命名：同 uri 稳定、不同 uri/扩展不冲突
- TrackKey/current 匹配保护
- 终态 source 匹配保护
- 旧曲 Finished 不映射当前 UI 的策略函数
- 导出相对目录带模型维度

---

## 2. 真机已验证

### A1 / A4：A 多轨 → 自动 B → B 极快

- A = `who_am_i.flac`，edge 多轨；
- 播完自动 B = `bullet.flac`；
- B 极快。

日志：

```text
TrackBridge: who_am_i -> cache/separate_input_c44e105d.flac
TrackBridge: bullet   -> cache/separate_input_7659cab2.flac
A edge cache: 3d42e864dce0c19a-spleeter
B edge cache: 25641fd5d493ca5e-spleeter
```

结论：B 使用自己的临时源/cache；未复用 A。

### C1 / C2：开关 off/on

- edge 自动多轨；
- 开关 off → 原曲；
- 开关 on → 多轨恢复。

结论：开关经 Domain attach/detach，播放器状态与 attachment 一致。

### E1：导出模型目录

2 轨 edge 导出：

```text
StemExporter: 导出 2 个文件 → Music/separate/bullet_44k/spleeter-2stem/
```

设备文件确认：

```text
Music/separate/bullet_44k/spleeter-2stem/
  vocals.wav
  accompaniment.wav
```

### F1：edge fast path

- edge 缓存命中自动切多轨，进程日志显示 `loadMultiTrack` 成功。

### B1：FULL 后台 + 当前 B（发现并修复一个 UI 残留）

- A=`who_am_i.flac` 启动 4-stem；切到 B=`bullet.flac`；
- A 在后台继续，最终 `4-stem done infer≈44.9s`；
- 当前 B 的播放页保持原曲，未出现 A 的完成/多轨；
- **验收发现**：分离页仍显示旧 A 的 `Preparing/解码音频`，因为 P0 过滤只跳过了映射，
  没有清掉 `SeparationTask`。
- **已修**：`SpikeViewModel.applyDomainSnapshot` 在旧曲 job 不匹配当前曲时
  `session.resetSeparation()` + `State.Idle`，不再把旧任务的 Preparing 留在 B 页面。
- **复验通过（2026-11-13 12:52）**：A 的 4-stem cache 完成 → 切 B → 分离页
  显示 B 原曲、无进度/无 A 完成态、导出禁用。

---

## 3. 已做但未形成完整回归的

- 4-stem 缓存/启动：之前版本已验证；v0.2.15 未重复全套。
- 旋转/后台：之前版本通过；本次未重复。
- 手动开关：在 v0.2.15 前一次构建通过；v0.2.15 无相关代码改动。

---

## 4. 未验收（下一步必须补）

| # | 场景 | 状态 |
|---|---|---|
| A2 | A→B 切换瞬间（<1s）点极快 | 未做 |
| A3 | A edge 还在跑时切 B | 未做 |
| B1 | FULL 4-stem A 后台，切 B，A 完成 | ✅ 复验通过 |
| B2 | 返回 A，缓存/手动恢复 | 未做 |
| D1 | 分离中取消 edge | 未做 |
| D2 | 播放自然结束 / FULL 后台继续 | 未做 |
| D3 | kill PlaybackService/SeparationService | 未完成：stop-service 未真正销毁，需用 force-stop/异常路径 |
| D4 | 进程死亡后重启 | ✅ 基础重启通过（无 FATAL） |
| E2 | 导出 MediaStore pending/重复检查 | 只查了目录名 |
| F2 | 旋转/锁屏媒体键 | ✅ 旋转通过；锁屏媒体键未单独测 |

注意：**未完成这些之前，不应把本轮视为“验收通过”，也不应继续 P2 直解码重构。**

---

## 5. 当前风险评估

- A1/A4 已证明主路径不会把 A 的 source/cache 给 B；但 A2/A3 的“旧任务未完成 + 切换瞬间”仍未覆盖。
- FULL 后台完成（B1）是 P0-5 的核心场景，未跑，因此 P0-5 还不能算完全验收。
- ServiceLost 接线只有编译验证，D3 必须补。
- 导出模型目录路径正确，但 E2 的 MediaStore 残留未查。

---

## 6. 建议下一步

1. 补 A2/A3/B1/B2/D1-D4/E2/F2。
2. 每项记录 `SepDomain/SepRepo/TrackBridge/loadMultiTrack` 日志。
3. 若发现 P0/P1 回归：
   - 开 `fix/v0.2.15-acceptance`；
   - 只修对应问题 + 加测试；
   - 重新跑失败项与 A1/A4。
4. 全部通过后再考虑 P2 直解码；否则停在 v0.2.15。
