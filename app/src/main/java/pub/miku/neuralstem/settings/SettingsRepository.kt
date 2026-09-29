package pub.miku.neuralstem.settings

import android.app.Application
import android.content.Context
import android.util.Log
import pub.miku.neuralstem.spike.ModelRegistry
import pub.miku.neuralstem.spike.ModelSpec

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

    /** 首启 benchmark 是否已询问过(避免每次启动都弹)。 */
    var benchmarkPrompted: Boolean
        get() = prefs.getBoolean(KEY_BENCH_PROMPTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BENCH_PROMPTED, value).apply()
        }

    /**
     * UI-V：黑胶转盘「基准一圈时长」（秒）。
     * 1.0× 时转一圈 = 该值；实际一圈 = 该值 ÷ speed。
     * 范围 [DECK_SEC_MIN, DECK_SEC_MAX]，步长 [DECK_SEC_STEP]，默认 [DECK_SEC_DEFAULT]。
     */
    var deckSecondsPerTurn: Int
        get() = prefs.getInt(KEY_DECK_SEC, DECK_SEC_DEFAULT)
        set(value) {
            val v = value.coerceIn(DECK_SEC_MIN, DECK_SEC_MAX)
            prefs.edit().putInt(KEY_DECK_SEC, v).apply()
            Log.i(TAG, "deckSecondsPerTurn=$v")
        }

    /**
     * ★R6：**速度范围设置**（单一项 X，>1）。
     *
     * 语义：速度滑块区间 = **[1/X, X]**（下限由上限 X 对称导出）。
     * - X ≈ 1  → 区间收窄到 [1, 1]（几乎只能 1.0×）；
     * - X = 4  → 区间 [0.25, 4.0]（最大行程）。
     *
     * 取值 [SPEED_X_MIN, SPEED_X_MAX]，步长 [SPEED_X_STEP]，默认 [SPEED_X_DEFAULT]（=1.4 → [0.714, 1.4]）。
     * 与“转盘一圈时长”是两个独立设置。
     */
    var speedRangeX: Float
        get() = prefs.getFloat(KEY_SPEED_X, SpeedRange.X_DEFAULT)
        set(v) {
            prefs.edit().putFloat(KEY_SPEED_X, SpeedRange.quantizeX(v)).apply()
        }

    /** 派生：速度滑块下限 = 1 / X（X 已夹紧，故恒 > 0）。 */
    val speedSliderMin: Float get() = SpeedRange.sliderMin(speedRangeX)

    /** 派生：速度滑块上限 = X。 */
    val speedSliderMax: Float get() = SpeedRange.sliderMax(speedRangeX)

    /**
     * 播放模式（顶部循环按钮）——三态：
     * - [RepeatMode.SEQUENTIAL]：**顺序**（播完歌单末尾停止）→ `REPEAT_MODE_OFF`
     * - [RepeatMode.LOOP_ALL]：**循环歌单** → `REPEAT_MODE_ALL`
     * - [RepeatMode.LOOP_ONE]：**单曲循环** → `REPEAT_MODE_ONE`
     *
     * ★ 取代旧的 `listEndBehavior`（LOOP/STOP）；旧值自动迁移：LOOP→LOOP_ALL、STOP→SEQUENTIAL。
     */
    var repeatMode: RepeatMode
        get() {
            val raw = prefs.getString(KEY_REPEAT_MODE, null)
            if (raw != null) {
                return runCatching { RepeatMode.valueOf(raw) }.getOrDefault(RepeatMode.LOOP_ALL)
            }
            // 迁移旧键
            val legacy = runCatching {
                ListEndBehavior.valueOf(prefs.getString(KEY_LIST_END, ListEndBehavior.LOOP.name)!!)
            }.getOrDefault(ListEndBehavior.LOOP)
            return when (legacy) {
                ListEndBehavior.LOOP -> RepeatMode.LOOP_ALL
                ListEndBehavior.STOP -> RepeatMode.SEQUENTIAL
            }
        }
        set(v) { prefs.edit().putString(KEY_REPEAT_MODE, v.name).apply() }

    /** 旧枚举（仅用于迁移）。 */
    private enum class ListEndBehavior { LOOP, STOP }

    enum class RepeatMode {
        SEQUENTIAL,
        LOOP_ALL,
        LOOP_ONE;

        /** 下一个模式（循环按钮点击顺序：顺序 → 循环歌单 → 单曲循环 → 顺序）。 */
        fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]

        /** 按钮显示文本。 */
        val label: String get() = when (this) {
            SEQUENTIAL -> "顺序"
            LOOP_ALL -> "循环歌单"
            LOOP_ONE -> "单曲循环"
        }

        /** 按钮显示字形（零图标依赖）。 */
        val glyph: String get() = when (this) {
            SEQUENTIAL -> "\u2192"     // →
            LOOP_ALL -> "\u21BB"       // ↻
            LOOP_ONE -> "\u21BB\u2081" // ↻₁
        }
    }

    /**
     * 当前**选中的歌单 id**（= 播放队列来源）。持久化，便于重启后仍“有选中歌单”。
     * （用户约定：不存在“无选中歌单”状态；未显式选时一律默认歌单。）
     */
    var activeListId: String
        get() = prefs.getString(KEY_ACTIVE_LIST, PlaylistStoreRef.DEFAULT_ID)
            ?: PlaylistStoreRef.DEFAULT_ID
        set(v) { prefs.edit().putString(KEY_ACTIVE_LIST, v).apply() }

    /** 仅用于引用默认歌单 id 常量（避免依赖）。 */
    private object PlaylistStoreRef { const val DEFAULT_ID = "default" }

    companion object {
        private const val TAG = "SettingsRepo"
        private const val PREFS = "settings"
        private const val KEY_DEFAULT_ENGINE = "default_engine_id"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_BENCH_PROMPTED = "benchmark_prompted"
        private const val KEY_DECK_SEC = "deck_seconds_per_turn"
        private const val KEY_SPEED_X = "speed_range_x"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        /** @deprecated 旧键（仅用于迁移到 [KEY_REPEAT_MODE]）。 */
        private const val KEY_LIST_END = "list_end_behavior"
        private const val KEY_ACTIVE_LIST = "active_list_id"

        const val DECK_SEC_DEFAULT = 18
        const val DECK_SEC_MIN = 9
        const val DECK_SEC_MAX = 36
        const val DECK_SEC_STEP = 3

        // ★R6 速度范围设置：单一项 X（>1），滑块区间 = [1/X, X]。
        //   取值范围/默认/量化逻辑见 [SpeedRange]（纯函数，可单测）。
        const val SPEED_X_MIN = SpeedRange.X_MIN
        const val SPEED_X_MAX = SpeedRange.X_MAX
        const val SPEED_X_STEP = SpeedRange.X_STEP
        const val SPEED_X_DEFAULT = SpeedRange.X_DEFAULT

        // 兼容常量：滑块范围的硬上下限（= 1/4 与 4）。
        const val SPEED_FLOOR = 0.25f
        const val SPEED_CEIL = 4.0f
    }
}
