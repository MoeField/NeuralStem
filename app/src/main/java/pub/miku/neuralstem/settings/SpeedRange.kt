package pub.miku.neuralstem.settings

import kotlin.math.roundToInt

/**
 * ★R6：速度范围设置（单一项 X，>1）的**纯函数**逻辑。
 *
 * 抽到顶层（无 Android 依赖）便于单测 —— [SettingsRepository] 需要 `Application`
 * 无法在 JVM 单测里实例化，真正的数学：量化与派生区间都在这里。
 *
 * ## 语义（用户定稿）
 * - 单一项 X（>1）；速度滑块区间 = **[1/X, X]**；
 * - X ∈ [1.2, 4]，step 0.1，默认 1.4（→ 滑块 [0.714, 1.4]）。
 *
 * ## 尺度
 * 播放速度滑块用**对数尺度**（区间 [1/X, X] 几何对称 → 1.0× 恒定居中）；
 * 本设置页 X 滑杆仍为**线性**。
 */
object SpeedRange {

    /** X 允许范围与步进（设置页滑杆口径）。 */
    const val X_MIN = 1.2f
    const val X_MAX = 4.0f
    const val X_STEP = 0.1f
    const val X_DEFAULT = 1.4f

    /**
     * 把任意 X 夹紧到 [X_MIN, X_MAX] 并量化到 [X_STEP] 网格。
     * 例：quantizeX(3.37f) == 3.4f；quantizeX(9f) == 4.0f；quantizeX(0.1f) == 1.2f。
     */
    fun quantizeX(v: Float): Float = quantizeToStep(v, X_MIN, X_MAX, X_STEP)

    /** 派生：滑块下限 = 1 / X（X 已夹紧，恒 > 0）。 */
    fun sliderMin(x: Float): Float = 1f / quantizeX(x)

    /** 派生：滑块上限 = X。 */
    fun sliderMax(x: Float): Float = quantizeX(x)

    /**
     * 通用量化：把 v 夹紧到 [min, max]，再就近舍入到 step 网格。
     * step <= 0 或 v 非有限时只夹紧不量化。
     */
    fun quantizeToStep(v: Float, min: Float, max: Float, step: Float): Float {
        if (!v.isFinite() || step <= 0f) return v.coerceIn(min, max)
        val clamped = v.coerceIn(min, max)
        val steps = ((clamped - min) / step).roundToInt()
        return (min + steps * step).coerceIn(min, max)
    }
}
