# 音轨分离技术调研报告(Spike 前置调研)

> 项目:NeuralStem — AI 音轨分离播放器(EE5415 个人项目)
> 调研人:开发过程中的 LLM 辅助调研,人工复核
> 日期:2026-02(学期第 3 周)
> 调研方法:GitHub API / raw README 直读一手来源,不依赖二手博客
> 对应文档:`doc/guide.md` §3.4 建议方向、§八.2 "优先验证核心风险"

---

## 1. 调研问题定义

需求(guide §3.4 + P3 功能组):
- 模型在设备本地运行(TFLite / ONNX Runtime / NNAPI),完全离线
- 至少 2-stem(人声/伴奏),理想 4-stem(人声/鼓/贝斯/其他)
- 4 分钟歌曲推理 ≤ 60s(中端设备)
- 输出独立 stem 文件(WAV/PCM)
- License 允许个人项目使用;模型体积/内存可控(N2 内存安全)

核心调研问题:**哪条"模型 × 运行时 × Android 管线"路线,能在个人项目时间盒内,同时满足音质可接受与 60s 时长门槛?**

---

## 2. 候选路线全景

### 路线 A — Spleeter → TFLite(主攻)

| 维度 | 内容 |
|------|------|
| 来源模型 | Spleeter(Deezer,MIT License),2/4/5-stems 预训练模型齐全(MUSD B18 高分) |
| 转换工具 | ① `tinoucas/spleeter-tflite-convert`(47⭐,2020,4stems→150MB tflite)② `Enuwbt/spleeter-tflite-conversion`(2025-05,含动态量化脚本 `quantizing/tflite_conversion.py`,产出 `model_flex_quant.tflite`) |
| 运行时 | TFLite + **Flex Delegate**(`tensorflow-lite-select-tf-ops` AAR,增重 ~40MB) |
| 输入/输出契约 | 输入:44.1kHz 立体声波形(float 或 int8 量化);输出:**直接是 vocals / accompaniment 波形**——**STFT/iSTFT/mask 全部在模型图内完成**,端上无需频谱处理代码(已核实 `Enuwbt/tflite_execution/run.py`:仅 load→resize→invoke→读 outputs) |
| 加速 | **无 GPU/NNAPI**:动态 shape 阻止 delegate 降级(tinoucas README 明确"tensors are dynamic-sized, GPU/Metal acceleration not possible"),只能 CPU 多线程 |
| 内存 | 已知大 RAM 消耗;实践共识:2 秒小块推理 + 4096 样本交叉淡化拼接(tinoucas) |
| 体积 | 4stems fp32 ≈150MB;动态量化后可显著压缩;2stems 应 <80MB(待实测) |
| 音质 | 2019 年模型,有可闻伪影;**对"扒谱/学歌"场景足够**(非出版级) |

**优势**:管线最短(STFT 在图内)→ Kotlin 代码量最小;转换脚本现成且有 2025 年维护版;MIT 无授权风险。
**劣势**:Flex Delegate 增包体;动态 shape 无法硬件加速;4 分钟全曲耗时是**最大未知数**。

### 路线 B — Spleeter → ONNX Runtime Mobile(回退)

| 维度 | 内容 |
|------|------|
| 依据 | `demixr/demixr-app`(89⭐,MIT,2026-07 活跃)已把 htdemucs 导出 ONNX 并在 Android CPU 上跑通,证明 ONNX Runtime Mobile 路线在 Android 上成立 |
| 差异点 | 若走 Spleeter→ONNX,**STFT/iSTFT 需移到 Kotlin 端**(ONNX 图通常只保留网络主体),需要自写 STFT/overlap-add 或引 JNI 库 |
| 运行时 | `onnxruntime-android` AAR,纯 CPU,无 Flex 依赖,包体更干净 |
| 定位 | 路线 A 的 Flex Delegate 加载失败/崩溃/耗时爆炸时的回退方案 |

### 路线 C — Demucs v4(htdemucs)→ ExecuTorch Vulkan(远期)

| 维度 | 内容 |
|------|------|
| 模型 | htdemucs(Meta,MIT),4-stems,**SOTA 音质**(htdemucs-ft 在 MUSD B18 上显著优于 Spleeter) |
| 现成产物 | `demixr/demucs-executorch` v1.0 release:`htdemucs_vulkan.pte`(Android)**302MB** |
| 输入契约 | `[1, 2, 343980]`(7.8s 立体声块 @44.1kHz);输出为 pre-mask 频谱 + 时域分支,**mask+iSTFT/overlap-add 要在 App 层做**(Demixr 用 Dart 实现,移植 Kotlin 需重写整条 DSP 管线) |
| 运行时 | ExecuTorch 0.4+(C++/JNI,Vulkan GPU delegate),Java/Kotlin 绑定需自行封装 |
| 结论 | 音质上限最高,但 302MB 模型 + JNI 集成 + DSP 管线重写,工作量数倍于路线 A。**列为远期升级项**,Spike 阶段不做 |

### 否决项

| 方案 | 否决原因 |
|------|----------|
| Spleeter 官方 SavedModel 直接跑 | 官方 README 明确无 TFLite/移动端支持;TF1 图无法直接上 Android |
| Open-Unmix / MDX-Net | 无成熟的端到端 Android/TFLite 移植;转换链条风险高 |
| 云端 API(如 LALAL.AI) | 违反离线优先(N1)与项目灵魂(F3.4) |

---

## 3. 关键技术事实(一手核实记录)

1. **Spleeter 官方仓库**(raw README 直读):MIT License;2stems(vocals/accompaniment)、4stems(+drums/bass)、5stems(+piano)模型齐全;未提 TFLite/移动端;有趣佐证:djay 的 Neural Mix 即基于 Spleeter 模型(第三方使用先例)。
2. **tinoucas/spleeter-tflite-convert**(raw README 直读):两步转换(export_model.py 路径硬编码 → tofreezelite.py,4stems 张量名硬编码);tflite ≈150MB;需 TFLite SelectTfOps;动态 shape 禁用 GPU;"sounds ok-ish with 2-second chunks using cross-faded overlap of 4096 samples"。
3. **Enuwbt/spleeter-tflite-conversion**(raw README + 源码直读):三步(gen/*.py → tflite_conversion.py 动态量化 `Optimize.DEFAULT` + `SELECT_TF_OPS` → run.py);run.py 证实:输入 44.1kHz 立体声(`librosa.load(sr=44100, mono=False)`),**脚本内无任何 STFT 调用** → 变换在图内;输出直接 vocals/accompaniment,归一化 int16 写 WAV;支持 shape_signature 动态 resize;int8 量化路径有 dequant 示例。
4. **demixr/demixr-app**(raw README 直读):双引擎(ExecuTorch Vulkan GPU 默认 / ONNX Runtime CPU 备选);4 分钟歌 GPU 比 CPU 快 ~2.5-8×(相对值,无绝对耗时);音频管线(解码/分块/overlap-add/iSTFT)在 App 层。
5. **demixr/demucs-executorch**(raw README 直读):htdemucs_vulkan.pte 302MB;输入 [1,2,343980];模型输出 pre-mask,后处理在端上。

---

## 4. 风险清单与缓解

| # | 风险 | 概率 | 影响 | 缓解 |
|---|------|------|------|------|
| R1 | Spleeter tflite 4 分钟全曲 CPU 推理 >>60s | 高 | 高 | Spike 先测 30s 片段外推;量化(int8 动态)压计算;必要时降级"片段式学习模式"或接受慢速后台处理(WorkManager 熬夜批处理,guide N5) |
| R2 | Flex Delegate 在部分机型 OOM/崩溃 | 中 | 高 | 回退路线 B(ONNX Runtime);降 2s→1s 块;`setNumThreads` 调优 |
| R3 | Flex AAR +150MB 模型导致 APK 过大 | 高 | 中 | Play Asset Delivery / 首次启动下载模型;或 2stems-only + 量化(<40MB 目标) |
| R4 | 2s 块拼接有爆音/断层 | 中 | 中 | 4096 样本交叉淡化(tinoucas 已验证 ok-ish);Spike 听感验收 |
| R5 | 转换脚本路径硬编码/TF 版本漂移 | 中 | 低 | 转换在 Mac 本地 Python 一次性完成,不进 CI;失败则用 tinoucas 现成 tflite |
| R6 | License 污染 | 低 | 高 | Spleeter/Demucs/转换脚本均 MIT;若借转换脚本代码(无 License 的 repo)只参考思路自行实现 |

---

## 5. 决策

**主攻路线 A(Spleeter 2-stem → TFLite + Flex)**,Spike 实测后决定:
- 模型规格:2-stem 起步(管线验证),实测合格再换 4-stem 对比音质
- 量化:fp32 先验证正确性 → 动态量化版对比耗时/内存 → 二选一
- **回退触发器**:Flex 加载失败 / OOM / 30s 片段外推 >90s → 启动路线 B 对比
- **远期**:若学期时间富余,以路线 C(htdemucs + ExecuTorch)做音质升级实验

**给 Phase 5(多轨播放)的预埋结论**(guide §3.6):
- Spleeter 输出已是整轨 PCM → **方案 A(多 ExoPlayer 实例)** 是默认架构;
- seek 对齐:各实例 `seekTo(同一 positionMs)` + `playWhenReady` 同步翻转,误差 <音频缓冲粒度,可接受;待 Spike 后在 Task 4 复核。

---

## 6. 引用来源

- https://github.com/deezer/spleeter (README, MIT)
- https://github.com/tinoucas/spleeter-tflite-convert (README, 47⭐)
- https://github.com/Enuwbt/spleeter-tflite-conversion (README + quantizing/tflite_conversion.py + tflite_execution/run.py)
- https://github.com/demixr/demixr-app (README, MIT, 89⭐)
- https://github.com/demixr/demucs-executorch (README + release v1.0 资产清单)
- 需求依据:doc/guide.md §3.4 / §3.6 / §八

---

*报告版本:v1.0 | 下一步:Task 0b 代码 Spike 验证本报告 §5 的假设*
