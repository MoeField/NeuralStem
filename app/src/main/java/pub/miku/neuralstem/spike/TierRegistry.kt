package pub.miku.neuralstem.spike

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
enum class StemCount(val label: String,val count: Int) {
    TWO("2 轨", 2),
    FOUR("4 轨", 4),
}

/** 档位（UI 按钮）。 */
enum class Tier(val label: String) {
    FAST("快速"),
    BALANCE("均衡"),
    QUALITY("质量"),
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

object TierRegistry {

    // 档位归属（按实测 RTF 分组，非硬编码“语义”；此处仅列 id）—— 声明在前，供下方 val 初始化用。
    private val BALANCE_IDS = setOf("uvr-9482", "uvr-KARA")
    private val QUALITY_IDS = setOf("uvr-Voc_FT", "uvr-Inst_HQ_3", "uvr-Main")

    /** 2 轨 · 快速 = Spleeter 2-stem（唯一）。 */
    val TWO_FAST = TierChoices(
        StemCount.TWO, Tier.FAST,
        candidates = listOf(SepOption.Sherpa(ModelRegistry.SPLEETER)),
        defaultId = ModelRegistry.SPLEETER.id,
    )

    /** 2 轨 · 均衡 = 组②（实测 ~1.0×/2.25×）。 */
    val TWO_BALANCE = TierChoices(
        StemCount.TWO, Tier.BALANCE,
        candidates = ModelRegistry.UVR_VARIANTS.filter { it.id in BALANCE_IDS }
            .map { SepOption.Sherpa(it) },
        defaultId = "uvr-9482",
    )

    /** 2 轨 · 质量 = 组③（实测 ~0.34×/0.72×）。 */
    val TWO_QUALITY = TierChoices(
        StemCount.TWO, Tier.QUALITY,
        candidates = ModelRegistry.UVR_VARIANTS.filter { it.id in QUALITY_IDS }
            .map { SepOption.Sherpa(it) },
        defaultId = "uvr-Voc_FT",
    )

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

    /** 全部（按 UI 顺序）。 */
    val ALL: List<TierChoices> = listOf(
        TWO_FAST, TWO_BALANCE, TWO_QUALITY,
        FOUR_FAST, FOUR_QUALITY,
    )

    /** 某轨数的档位（UI 一行）。 */
    fun forStem(stem: StemCount): List<TierChoices> = ALL.filter { it.stem == stem }

    fun find(stem: StemCount, tier: Tier): TierChoices? =
        ALL.firstOrNull { it.stem == stem && it.tier == tier }
}
