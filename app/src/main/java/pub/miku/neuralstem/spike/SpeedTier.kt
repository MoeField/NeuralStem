package pub.miku.neuralstem.spike

import androidx.annotation.StringRes
import pub.miku.neuralstem.R

/**
 * ★2 轨分离的**速度簇**（按实测 RTF 自动归属）。
 *
 * ## 与 [Tier] 的区别
 * - [Tier]（`FAST/BALANCE/QUALITY`）是**旧的手写档位**，仅供 **4 轨**继续使用（模型少，不需要动态化）。
 * - 本枚举是 **2 轨**的新分级：由 `DeviceBenchmark` 实测 RTF **自动判定**，
 *   换设备后分组会自动重排（这才是"按设备能力分级"的正确做法）。
 *
 * ## 为何不显示具体数字
 * 「慢」档实测跨 **0.41–0.61×**（差 50%），用一个数字概括不准。
 * ⇒ 分离页按钮**只显示档位名**；区间说明放在设置页的小字里。
 *
 * ## 实测依据（BF00A / G100，见 `doc/model_speed_tiers.md`）
 * ```
 * 极快  ≥2.0×    Spleeter 2-stem 8.56× / 4-stem 3.15×
 * 临界  0.9–2.0×  UVR 28MB 系（9482/KARA/1_9703/2_9682/3_9662）1.00–1.04×
 * 慢    0.4–0.9×  UVR 50–56MB 系 + Demucs 0.41–0.61×
 * 很慢  <0.4×    UVR 64MB 系 0.34–0.35×
 * ```
 */
enum class SpeedTier(
    /** 下界（含）。 */
    val minRtf: Float,
    /** 上界（不含；[ULTRA] 无上界）。 */
    val maxRtf: Float,
    @StringRes val labelRes: Int,
) {
    /** ≥ 2× —— 边分离边播无压力。 */
    ULTRA(2.0f, Float.MAX_VALUE, R.string.speed_tier_ultra),

    /** 0.9 – 2× —— 分离 ≈ 播放时长（边播会卡在分离点）。 */
    CRITICAL(0.9f, 2.0f, R.string.speed_tier_critical),

    /** 0.4 – 0.9× —— 慢于播放，只能"先分离后播放"。 */
    SLOW(0.4f, 0.9f, R.string.speed_tier_slow),

    /** < 0.4× —— 音质优先，需耐心等。 */
    VERY_SLOW(0f, 0.4f, R.string.speed_tier_very_slow),
    ;

    companion object {
        /** UI 顺序（快 → 慢）。 */
        val ordered: List<SpeedTier> = listOf(ULTRA, CRITICAL, SLOW, VERY_SLOW)

        /**
         * 按实测 RTF 归属。
         *
         * **未测（`null`）→ [VERY_SLOW]**（保守：不把没测过的模型当实时档，
         * 与 `SettingsViewModel` 的"未检测 → 🐢 很慢"策略一致）。
         */
        fun of(rtf: Float?): SpeedTier = when {
            rtf == null -> VERY_SLOW
            rtf >= ULTRA.minRtf -> ULTRA
            rtf >= CRITICAL.minRtf -> CRITICAL
            rtf >= SLOW.minRtf -> SLOW
            else -> VERY_SLOW
        }
    }
}
