package pub.miku.neuralstem.spike

import androidx.annotation.StringRes
import pub.miku.neuralstem.R

/**
 * ★分离「档位」注册表（分离页按钮 = 档位；每档候选模型在设置里选）。
 *
 * ## 语义（用户定稿 2026-10-05）
 * - 分离页按**轨数**分两行：**2 轨** / **4 轨**。
 * - **2 轨**：3 档 —— `快速 / 均衡 / 质量`。
 * - **4 轨**：2 档 —— `快速 / 质量`（**无均衡**）。
 * - 每档是**候选列表**，用户在设置里选具体模型；按钮显示档位名 + 选中模型名。
 * - 档位归属 = **benchmark 实测 RTF** 分组（组②→均衡、组③→质量；非硬编码概念，见下）。
 *
 * ## 实测分组依据（`benchmark_2026-10-04.artifact.md`；BF00A 与 Gen3 一致）
 * ```
 * 组① ~8.5× / 18×   : Spleeter 2-stem
 * 组② ~1.0× / 2.25×  : UVR 9482, UVR KARA
 * 组③ ~0.34× / 0.72× : UVR Voc_FT, Inst_HQ_3, Main
 * 4 轨：Spleeter 4-stem（3.15×/6.93×）；Demucs（已修 OOM）
 * ```
 */

/** 轨数（UI 行）。 */
enum class StemCount(@StringRes val labelRes: Int, val count: Int) {
    TWO(R.string.stem_2, 2),
    FOUR(R.string.stem_4, 4),
}

/** 档位（UI 按钮）。 */
enum class Tier(@StringRes val labelRes: Int) {
    FAST(R.string.tier_fast),
    BALANCE(R.string.tier_balance),
    QUALITY(R.string.tier_quality),
}

/**
 * 一次分离的「可选项」—— 抽象"怎么跑"，屏蔽底层三套引擎
 * （sherpa 2-stem/UVR、Spleeter4、Demucs）。
 */
sealed interface SepOption {
    /** 稳定 id（用于偏好/缓存键）。 */
    val id: String
    /** UI 显示名。 */
    val displayName: String

    /** sherpa 引擎（2-stem 或 UVR）—— 走 [SeparationRepository.start]。 */
    data class Sherpa(val spec: ModelSpec) : SepOption {
        override val id get() = spec.id
        override val displayName get() = spec.displayName
    }

    /** Spleeter 4-stem（自建 DSP）—— 走 [SeparationRepository.start4]。 */
    data object Spleeter4 : SepOption {
        override val id = "spleeter4"
        override val displayName = "Spleeter 4-stem"
    }

    /** Demucs htdemucs —— 走 [SeparationRepository.startDemucs]。 */
    data object Demucs : SepOption {
        override val id = "demucs"
        override val displayName = "Demucs"
    }
}

/**
 * 一个「档位选择」：轨数 × 档位 → 候选模型列表 + 默认。
 *
 * ⚠️ **仅供 4 轨使用**（模型少且固定，不需要按速度动态分级）。
 * 2 轨已改为 [TierChoices2]（按 [SpeedTier] 动态生成）。
 */
data class TierChoices(
    val stem: StemCount,
    val tier: Tier,
    val candidates: List<SepOption>,
    val defaultId: String,
) {
    fun default(): SepOption =
        candidates.firstOrNull { it.id == defaultId } ?: candidates.first()
}

/**
 * ★2 轨的「速度簇档位」：一个 [SpeedTier] + 其候选模型。
 *
 * 与 [TierChoices] 的区别：
 * - **不含 [Tier]** —— 2 轨的档位语义已从「快速/均衡/质量」改为「极快/临界/慢/很慢」；
 * - **候选由实测 RTF 自动归属**（`DeviceBenchmark.cached()`）→ 换设备自动重排。
 * - 模型只来自 [ModelRegistry.ALL]（Spleeter 2-stem + 5 个正式 UVR），
 *   实验模型（[ModelRegistry.EXPERIMENTAL]）**不进档位**。
 */
data class TierChoices2(
    val speedTier: SpeedTier,
    /** 该簇内的候选（已装/未装都列，UI 自行过滤）。 */
    val candidates: List<SepOption.Sherpa>,
) {
    val stem: StemCount get() = StemCount.TWO

    /** 是否有任何已安装的候选（无则不显示该档按钮）。 */
    fun hasInstalled(installedIds: Set<String>): Boolean =
        candidates.any { it.id in installedIds }
}

object TierRegistry {

    // ==================== 4 轨（不变）====================

    /** 4 轨 · 快速 = Spleeter 4-stem。 */
    val FOUR_FAST = TierChoices(
        StemCount.FOUR, Tier.FAST,
        candidates = listOf(SepOption.Spleeter4),
        defaultId = SepOption.Spleeter4.id,
    )

    /** 4 轨 · 质量 = Demucs。 */
    val FOUR_QUALITY = TierChoices(
        StemCount.FOUR, Tier.QUALITY,
        candidates = listOf(SepOption.Demucs),
        defaultId = SepOption.Demucs.id,
    )

    /** 4 轨的全部档位（固定 2 档，不支持禁用）。 */
    val FOUR_TIERS: List<TierChoices> = listOf(FOUR_FAST, FOUR_QUALITY)

    // ==================== 2 轨（★按速度簇动态）====================

    /**
     * 生成 2 轨的 4 个速度簇（按实测 RTF 归属）。
     *
     * - 模型池 = [ModelRegistry.ALL] 中 **engine = sherpa** 的（Spleeter 2-stem + 5 个正式 UVR）；
     *   4-stem / Demucs 不属 2 轨，不进。
     * - **空簇会被返回但 candidates 为空** → UI 自己过滤（`hasInstalled`）。
     *   这样调用方能判断“用户禁用了几个簇”，而不是让簇凭空消失。
     *
     * @param rtf 实测 RTF（来自 `DeviceBenchmark.cached()`，键 = modelId）。无记录的模型归 VERY_SLOW。
     */
    fun twoStemTiers(rtf: Map<String, Float>): List<TierChoices2> {
        val pool = ModelRegistry.ALL
            .filter { it.engine == SherpaSourceSeparation.MODE_SPLEETER ||
                it.engine == SherpaSourceSeparation.MODE_UVR }
            .map { SepOption.Sherpa(it) }
        val grouped = pool.groupBy { SpeedTier.of(rtf[it.id]) }
        return SpeedTier.ordered.map { tier ->
            TierChoices2(speedTier = tier, candidates = grouped[tier].orEmpty())
        }
    }

    /** 向后兼容：旧代码可能需要“全部档位”的平铺列表（仅 4 轨）。 */
    val ALL: List<TierChoices> = FOUR_TIERS

    /** 4 轨某档位。 */
    fun find(stem: StemCount, tier: Tier): TierChoices? =
        FOUR_TIERS.firstOrNull { it.stem == stem && it.tier == tier }
}
