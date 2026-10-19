# 分离模型实测（BF00A「蓝狐」，2026-10-04）

> 目的：为「分离页分级 + 分组」取**统一口径**的实测数据。
> 设备：BF00A（Aura_A1）/ Android 16 / 12GB / arm64 / 联发科 Helio G100。
> 方法：① `DeviceBenchmark`（LONG，合成音频，纯推理 RTF）；② 端到端分离（完整歌）。
> ⚠️ 全部为**前台**运行。

---

## 一、DeviceBenchmark（LONG，3 轮取代表值）—— 纯推理 RTF

| 模型 | chunk | rtf | 3 轮耗时(ms) |
|---|---|---|---|
| **Spleeter 2-stem fp16** | 30.0s | **8.54×** | [3547, 3511, 3496] |
| UVR 9482 | 5.0s | **1.03×** | [4918, 4836, 4772] |
| UVR KARA | 5.0s | **1.03×** | [4934, 4840, 4802] |
| UVR Voc_FT | 5.0s | **0.35×** | [14540, 14091, 14171] |
| UVR Inst_HQ_3 | 5.0s | **0.34×** | [14664, 14546, 14637] |
| UVR Main | 5.0s | **0.34×** | [14513, 14417, 14714] |

> ⚠️ benchmark **不含 4-stem / Demucs**（不在 `ModelRegistry.ALL`）。

---

## 二、端到端分离（完整歌 = Good Night 5:14 = 314045ms 音频）

| 引擎/模型 | infer | 实时 | peakPss | 备注 |
|---|---|---|---|---|
| Spleeter 2-stem | 30.2s（历史） | **7.9×** | 740MB | |
| **Spleeter 4-stem** | **99803ms** | **3.15×** | 1176.9MB | 本机实测 |
| **UVR 9482** | **310508ms** | **1.01×** | 1140.4MB | 与 benchmark 1.03× 一致 |
| **Demucs htdemucs** | ~~❌ 失败~~ → **✅ 已修复** | **0.61×**（5:14） | 4933.9 MB | **Java 堆 OOM 已修（`fc463cf`）** |

> [!IMPORTANT]
> **本节原结论「Demucs 完整歌不可用」已被推翻，保留仅为追溯。**
> 根因是 `DemucsSeparator.acc = Array(4){Array(2){FloatArray(total)}}` 常驻 Java 堆 443 MB，
> 已由 **`fc463cf`** 改为**滑动窗口 O(WAVE)≈7 MB**（块处理完即归一化+落盘+丢弃）。
>
> **修复后实测（BF00A 完整歌 5:14）**：
> ```
> demucs done: in=out=13849391 blocks=54, infer=516803ms realtime=0.61x, peakPss=4933.9MB
> ```
> 四轨等长 ✅。遗留的 ~4.9 GB 是 **native ORT arena**（非 Java 堆），见
> [`m4_evidence.md`](m4_evidence.md) §8。
>
> **Demucs 的完整跨设备数据（BF00A 0.82× / Gen3 1.83×）见 [`m4_evidence.md`](m4_evidence.md)。**

---

## 三、初步分组观察（供讨论，非定稿）

按**纯推理 rtf**（口径一）：

| 组（差 ≤0.25×） | 模型 |
|---|---|
| **~8.5×** | Spleeter 2-stem |
| **~1.03×** | UVR 9482, UVR KARA（差 0.00） |
| **~0.34×** | UVR Voc_FT(0.35), Inst_HQ_3(0.34), Main(0.34) |

⇒ 天然分成 **3 组**：`{Spleeter2}` / `{9482, KARA}` / `{Voc_FT, Inst_HQ_3, Main}`。
（4-stem 3.15×、Demucs 失败 → 另议。）

> ⚠️ 待补：UVR 9482 端到端；以及"实时分离/边分离边播"尚未做（实时模型也要整首跑完）。
