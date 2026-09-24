# NeuralStem 实施计划 (Spike 优先策略)

> 依据：`doc/guide.md` v1.0 | 更新：2026-02 学期第 3 周
> 策略：先排雷最大技术风险（本地音轨分离），再搭建播放器主流程
> 路线：Spleeter 2-stem → ONNX，经 sherpa-onnx 运行时（选型变更理由见 `doc/research_separation.md` §5）

---

## 进度总览

| Task | 内容 | 状态 | 归档 |
|------|------|------|------|
| 0a | 分离选型调研（TFLite → ONNX/sherpa-onnx） | ✅ | `doc/research_separation.md` |
| 0b | Spike 工程（JNI + AAR + CMake） | ✅ | `.critical_steps.md` §Task0b |
| 0c | 自动化验收通道（应用内自驱动） | ✅ Phase A/C；Phase B 已补（V5） | `.critical_steps.md` §Task0c |
| 0d | 26s 片段首次实测（18.7× 实时） | ✅ | `.critical_steps.md` §Task0d |
| 0e | 长音频流式改造 + 5:14 全曲实测 | ✅ | `.memory.md` §Task0e |
| 0g | 6G RAM 环境全量重测 | ✅ | `.memory.md` §Task0g |
| 1 | Phase 1：基础播放器（Media3/ExoPlayer） | ✅ | `.memory.md` §Task1 |
| 2 | Phase 2：波形渲染与拖动跳转 | ✅ | `.memory.md` §Task2 |
| 3 | Phase 3：变速变调（SonicAudioProcessor） | ✅ | `.memory.md` §Task3 |
| 4 | Phase 5：多轨同步播放 + 分离接入主流程 | ✅ | `.memory.md` §Task4 |
| **V** | **阶段 V：功能可行性验证（V0–V5）** | ✅ 完成 | `.memory.md` §阶段 V |
| **M** | **阶段 M：分离模型探索（UVR/变体/4-stem/边分离边播）** | ✅ **完成** | `.memory.md` §阶段 M |
| **M2** | **阶段 M2：引擎探测 / 模型分组 / 导航壳 / EQ（A–G）** | ✅ **完成** | `.memory.md` §阶段 M2 |
| **M3** | **阶段 M3：多轨+EQ 搬进 PlaybackService（架构统一）** | ✅ **完成** | `.memory.md` §阶段 M3 |
| **M3.5** | **阶段 M3.5：M3 架构变更的实测补强（T1–T4）** | ✅ **完成** | `.memory.md` §阶段 M3.5 |
| 5 | Phase 6：UX 打磨（F5.x） | ⬜ **用户推迟（UI 设计讨论）** | — |

### 阶段 M3.5 实测补强（T1–T4）
| 项 | 内容 | 结果 |
|---|---|---|
| T1 | 多轨 EQ 正确性 | ✅ 抽 `BandEq` 接口 + 注入工厂；`EqControllerTest` 12 项（单测 47→59）|
| T2 | 单↔多轨切换 | ✅ **抓到并修复** EQ 漏挂 bug（坑 47）|
| T3 | 多轨 seek + 变速同步 | ✅ `MIRROR seek` / `MIRROR params` 均到达跟随轨 |
| T4 | 切歌 EQ 重挂 | ✅ `player READY → attachSingleEq` |

### 阶段 M3 任务链
| 子任务 | 内容 | 结果 |
|---|---|---|
| P1 | `playbackRouter`（ForwardingPlayer）| ❌ **已废弃删除**（导致多轨无通知，见坑 45）|
| P2 | 多轨搬进 Service（**v2 架构**）| ✅ session 永远认真实 `singlePlayer`；多轨主轨复用它 → 通知/前台服务/后台续播均过 |
| P3 | EQ 搬进 Service | ✅ 单/多轨 EQ 均可用（两链均在 audio_flinger）|
| P4 | 真机综合验收 | ✅ 单轨/多轨 × 播放/后台/前台服务/通知/EQ 均通过；47 单测 + 6 仪器化全绿 |
| P5 | 文档归档 | ✅ |

### 阶段 M2 任务链（全部完成）
| 任务 | 内容 | 结果 |
|---|---|---|
| A | 模型元数据 + 修 Inst 反标签 | ✅ `ModelSpec`/`StemOrder`；NCC +0.032→+0.995 |
| B | 设备 benchmark | ✅ 真机 6 模型 RTF 与阶段 M 一致；缓存已验证 |
| C | 应用导航壳（Drawer） | ✅ `AppShell`；`SpikeActivity`→`SeparationScreen`；移除 setResult 链路 |
| D | EQ（spike + 接入） | ✅ 平台 `Equalizer` 可用；单/多轨均挂上并同步 |
| E | 设置页 | ✅ 三档分组 + 默认引擎 + EQ 开关 + 首启弹窗 |
| F | 接入分离流程 | ✅ 默认引擎生效（真机验证） |
| G | 文档归档 | ✅ |

### 阶段 M 结论（模型探索）

> ⚠️ 所有实时性数字均为**中端机 BF00A** 上实测；换更强设备会变快，需重测。
> 选型看**相对代价**（UVR 比 Spleeter 贵 8–24× 算力），不看绝对秒数。

| 项 | 结论 |
|---|---|
| ① UVR 真机 | Voc_FT 0.339× / 2024MB（vs Spleeter 7.91× / 707MB）|
| ② 变体扫（5 个）| 小模型 28MB 比大模型 64MB 快 ~3×、内存减半；Inst_HQ_3 伴奏最强 |
| ③ 相对代价 | UVR 全系比 Spleeter 贵 8–24× 算力 → **建议 Spleeter 默认、UVR 可选** |
| ④ 4-stem | 上游硬编码 2；但找到现成 `Best-Practice/spleeter-4stems-onnx` + DSP 契约，需自建 DSP（~1.5–3 人周）→ 本轮不做 |
| ⑤ 边分离边播 | 本机上对 Spleeter 成立（B1 自定义 DataSource，~3–5 天）；对 UVR 部分设备不成立 → 本轮不实现 |

**关键建议：Spleeter 作默认引擎；UVR 作可选（带耗时预估）。**

### 最终验收数字
- 单测：**59 项**全绿；仪器化：**6 项**全绿（真机）
- 4 分钟歌分离（真机、前台、中位数）：mp3 **30.2s**、wav **30.7s**（≤60s ✅ 余量 2×）
- 多轨 seek 对齐：文件级 lag = 0 采样点
- N4：切后台继续播放 + 媒体通知（前台服务）
- M2：benchmark（Spleeter 8.4× 等）+ 三档分组 + EQ（单/多轨）均真机验证

---

## 后续（未开始）

### Task 5 — Phase 6：UX 打磨（P5）+ DJ 控制台 —— **用户推迟**
- F5.1 各轨波形叠加 / F5.2 横屏 / F5.3 深色 / F5.4 触感 / F5.5 小组件
- **DJ 控制台设想**（转盘/BPM/音高旋钮/crossfader/EQ 面板）—— 见计划附录
- 用户指示：UI 设计统一讨论（BPM/音高/crossfader 已由现有 API 支持；EQ 引擎已通）

### 新建议（阶段 V/M2 暴露）
- **分离任务前台化**：V4 发现应用在后台时被降频 ~2×（30.7s→64.7s）
- **EQ 下沉到 PlaybackService**：使其跟播放一样后台存活（N4）
- **音质增强**：UVR-MDX 真机重试 / htdemucs（HANDOFF §八.B）

---

## 里程碑约定
- 每个 Task 完成后更新 `doc/.critical_steps.md`（步骤/踩坑）和 `doc/.memory.md`（过程笔记）
- 关键选型变更需在代码注释/文档中记录理由（guide §八.1）
- **每完成一步先写文档再继续下一步**（Task 0e 的 WavIo 改造即因未遵守此约定而「做了半截、主链路没接上」）
