package pub.miku.neuralstem.settings

import android.app.Application
import android.content.Context
import android.util.Log
import pub.miku.neuralstem.spike.ModelRegistry
import pub.miku.neuralstem.spike.ModelSpec
import pub.miku.neuralstem.spike.SepOption
import pub.miku.neuralstem.spike.StemCount
import pub.miku.neuralstem.spike.Tier
import pub.miku.neuralstem.spike.TierRegistry

/**
 * 设置持久化(阶段 M2 任务 E)。
 *
 * 目前存两件事:
 * - **默认分离引擎**(必须以 [ModelSpec.id] 存，以便模型列表变动后仍能兑回)
 * - **EQ 开关状态**
 *
 * 用 [SharedPreferences]（与 DeviceBenchmark 一致，项目暂未引 Room/DataStore）。
 */
class SettingsRepository(app: Application) {

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 默认引擎(按 id 存；查不到则回退 [ModelRegistry.DEFAULT])。 */
    var defaultEngineId: String
        get() = prefs.getString(KEY_DEFAULT_ENGINE, null) ?: ModelRegistry.DEFAULT.id
        set(value) {
            prefs.edit().putString(KEY_DEFAULT_ENGINE, value).apply()
            Log.i(TAG, "defaultEngineId=$value")
        }

    /** 解析为 [ModelSpec]（id 无效时回退默认）。 */
    fun defaultEngine(): ModelSpec = ModelRegistry.byId(defaultEngineId) ?: ModelRegistry.DEFAULT

    /** EQ 开关。 */
    var eqEnabled: Boolean
        get() = prefs.getBoolean(KEY_EQ_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EQ_ENABLED, value).apply()
            Log.i(TAG, "eqEnabled=$value")
        }

    /**
     * ★G-3：“边分离边播”开关（**默认关**）。
     * 开启后：分离从当前播放位置起（不从头），首段就绪自动切到分离结果播放。
     * 仅 Spleeter 2-stem（快速档）生效。
     */
    var edgeSeparationEnabled: Boolean
        get() = prefs.getBoolean(KEY_EDGE_SEP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EDGE_SEP, value).apply()
            Log.i(TAG, "edgeSeparationEnabled=$value")
        }

    /** 首启 benchmark 是否已询问过(避免每次启动都弹)。 */
    var benchmarkPrompted: Boolean
        get() = prefs.getBoolean(KEY_BENCH_PROMPTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BENCH_PROMPTED, value).apply()
        }

    /**
     * 播放模式（播放页**循环按钮**）。三态：
     * - [RepeatMode.SEQUENTIAL]：顺序（播完歌单停止）→ `REPEAT_MODE_OFF`
     * - [RepeatMode.LOOP_ALL]：循环歌单 → `REPEAT_MODE_ALL`
     * - [RepeatMode.LOOP_ONE]：单曲循环 → `REPEAT_MODE_ONE`
     *
     * 默认 [RepeatMode.SEQUENTIAL]（= 9-24 原先行为：不设 repeatMode）。
     */
    var repeatMode: RepeatMode
        get() = runCatching {
            RepeatMode.valueOf(prefs.getString(KEY_REPEAT_MODE, "") ?: "")
        }.getOrDefault(RepeatMode.SEQUENTIAL)
        set(v) { prefs.edit().putString(KEY_REPEAT_MODE, v.name).apply() }

    enum class RepeatMode {
        SEQUENTIAL,
        LOOP_ALL,
        LOOP_ONE;

        /** 下一个模式（循环按钮点击顺序）。 */
        fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]

        /** 按钮/提示文本。 */
        val label: String get() = when (this) {
            SEQUENTIAL -> "顺序"
            LOOP_ALL -> "循环歌单"
            LOOP_ONE -> "单曲循环"
        }

        /** 按钮字形（零图标依赖）。 */
        val glyph: String get() = when (this) {
            SEQUENTIAL -> "\u2192"     // →
            LOOP_ALL -> "\u21BB"       // ↻
            LOOP_ONE -> "\u21BB\u2081" // ↻₁
        }
    }

    /**
     * 黑胶转盘「基准一圈时长」（秒）。1.0× 时转一圈 = 该值；实际 = 值 ÷ speed。
     * 范围 [DECK_SEC_MIN, DECK_SEC_MAX]，步长 [DECK_SEC_STEP]，默认 [DECK_SEC_DEFAULT]。
     */
    var deckSecondsPerTurn: Int
        get() = prefs.getInt(KEY_DECK_SEC, DECK_SEC_DEFAULT)
        set(value) {
            val v = value.coerceIn(DECK_SEC_MIN, DECK_SEC_MAX)
            prefs.edit().putInt(KEY_DECK_SEC, v).apply()
        }

    /**
     * 变速范围设置（单一项 X，>1）。速度滑块区间 = **[1/X, X]**。
     * X ∈ [SpeedRange.X_MIN, X_MAX]，step [SpeedRange.X_STEP]，默认 [SpeedRange.X_DEFAULT]。
     */
    var speedRangeX: Float
        get() = prefs.getFloat(KEY_SPEED_X, SpeedRange.X_DEFAULT)
        set(v) {
            val q = SpeedRange.quantizeX(v)
            prefs.edit().putFloat(KEY_SPEED_X, q).apply()
        }

    /** 派生：速度滑块下限 = 1 / X（X 已夹紧，恒 > 0）。 */
    val speedSliderMin: Float get() = SpeedRange.sliderMin(speedRangeX)

    /** 派生：速度滑块上限 = X。 */
    val speedSliderMax: Float get() = SpeedRange.sliderMax(speedRangeX)

    // ============================ ★分离档位：每档选模型（D-2）============================
    //
    // 只有「候选 ≥ 2」的档需要在设置里选（2轨·均衡、2轨·质量）；其余档固定单模型。
    // 按 `<stem>_<tier>` 存 **SepOption.id**（模型/引擎稳定 id）。

    /** 某档选中的模型 id（默认 = 该档 [TierChoices.defaultId]）。 */
    fun tierModelId(stem: StemCount, tier: Tier): String {
        val def = TierRegistry.find(stem, tier)?.defaultId ?: ""
        return prefs.getString("tier_${stem.name}_${tier.name}", def) ?: def
    }

    /** 设置某档的模型 id（仅接受该档候选内的 id，否则忽略）。 */
    fun setTierModelId(stem: StemCount, tier: Tier, id: String) {
        val choices = TierRegistry.find(stem, tier) ?: return
        if (choices.candidates.none { it.id == id }) {
            Log.w(TAG, "setTierModelId 忽略非法 id=$id for $stem/$tier")
            return
        }
        prefs.edit().putString("tier_${stem.name}_${tier.name}", id).apply()
        Log.i(TAG, "tierModel $stem/$tier = $id")
    }

    /** 解析某档选中的 [SepOption]（id 无效时回退默认）。 */
    fun tierOption(stem: StemCount, tier: Tier): SepOption? {
        val choices = TierRegistry.find(stem, tier) ?: return null
        val id = tierModelId(stem, tier)
        return choices.candidates.firstOrNull { it.id == id } ?: choices.default()
    }

    companion object {
        private const val TAG = "SettingsRepo"
        private const val PREFS = "settings"
        private const val KEY_DEFAULT_ENGINE = "default_engine_id"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_BENCH_PROMPTED = "benchmark_prompted"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_DECK_SEC = "deck_seconds_per_turn"
        private const val KEY_SPEED_X = "speed_range_x"
        private const val KEY_EDGE_SEP = "edge_separation_enabled"

        const val DECK_SEC_DEFAULT = 18
        const val DECK_SEC_MIN = 9
        const val DECK_SEC_MAX = 36
        const val DECK_SEC_STEP = 3
    }
}
