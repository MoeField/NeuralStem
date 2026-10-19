package pub.miku.neuralstem.settings

import androidx.annotation.StringRes
import pub.miku.neuralstem.R
import android.app.Application
import android.content.Context
import android.util.Log
import pub.miku.neuralstem.spike.ModelRegistry
import pub.miku.neuralstem.spike.ModelSpec
import pub.miku.neuralstem.spike.SepOption
import pub.miku.neuralstem.spike.SpeedTier
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
     * ★G-3：“边分离边播”开关（**默认开**）。
     *
     * 开启后：分离从当前播放位置起（不从头），首段就绪自动切到分离结果播放。
     * 仅 Spleeter 2-stem（快速档）生效。
     *
     * 为何默认开：G-3 坐标系 bug（`a756885`）已修复并在真机三位置验收；
     * 不开时用户点「⚡ 快速」仍需等整首分离完成才能听，与“快速”的语义相悖。
     * 保留设置项开关 → 若某些曲目触发问题，用户仍可关掉回退。
     */
    var edgeSeparationEnabled: Boolean
        get() = prefs.getBoolean(KEY_EDGE_SEP, true)
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
     * ★模型下载：**下载源偏好**（默认 [ModelSourcePreference.AUTO]）。
     *
     * AUTO = 运行时探测（HEAD 目标域名），官方优先、不通则用 hf-mirror。
     */
    var modelSourcePreference: ModelSourcePreference
        get() = runCatching {
            ModelSourcePreference.valueOf(
                prefs.getString(KEY_MODEL_SOURCE, null)
                    ?: ModelSourcePreference.AUTO.name,
            )
        }.getOrDefault(ModelSourcePreference.AUTO)
        set(value) {
            prefs.edit().putString(KEY_MODEL_SOURCE, value.name).apply()
            Log.i(TAG, "modelSourcePreference=$value")
        }

    /** ★模型下载：上次 AUTO 探测到的实际源（仅用于设置页展示）。 */
    var lastResolvedModelSource: String?
        get() = prefs.getString(KEY_MODEL_RESOLVED, null)
        set(value) {
            prefs.edit().putString(KEY_MODEL_RESOLVED, value).apply()
        }

    /**
     * ★模型下载：**GitHub 代理前缀**（默认 `https://api.gitproxy.dev`）。
     *
     * ## 为何需要
     * UVR 系模型只在 **GitHub Release**（hf-mirror 镜像不了），而国内网络常见
     * `github.com:443` 连不上（TCP 超时）。代理做**前缀替换**：
     * `api.gitproxy.dev/github.com/<path>` → `github.com/<path>`（Cloudflare 加速）。
     *
     * 空字符串 = 直连 GitHub（若你在国外或自建了网络）。
     */
    var downloadProxy: String
        get() = prefs.getString(KEY_DOWNLOAD_PROXY, DEFAULT_GITHUB_PROXY) ?: DEFAULT_GITHUB_PROXY
        set(value) {
            prefs.edit().putString(KEY_DOWNLOAD_PROXY, value.trim()).apply()
            Log.i(TAG, "downloadProxy=${value.ifBlank { "(直连)" }}")
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

        /** 按钮/提示文本（i18n：改为字符串资源）。 */
        @get:StringRes
        val labelRes: Int get() = when (this) {
            SEQUENTIAL -> R.string.repeat_sequential
            LOOP_ALL -> R.string.repeat_loop_all
            LOOP_ONE -> R.string.repeat_loop_one
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

    // ============================ ★2 轨：按速度簇（2026-10-20 重构）============================
    //
    // 2 轨不再用「快速/均衡/质量」，改为 4 个速度簇。每簇两个开关：
    //   - `twoTierEnabled_<SpeedTier>`：是否在分离页显示该档（默认 true）
    //   - `twoTierModel_<SpeedTier>`：该档选中的模型 id（默认 = 该簇内第一个已装模型）
    //
    // ⚠️ **不做偏好迁移**（用户确认：单人使用，旧 `tier_TWO_*` key 自然失效）。

    /** 该速度簇是否启用（默认 true）。 */
    fun twoTierEnabled(tier: SpeedTier): Boolean =
        prefs.getBoolean("twoTierEnabled_${tier.name}", true)

    /** 设置某速度簇是否启用。 */
    fun setTwoTierEnabled(tier: SpeedTier, enabled: Boolean) {
        prefs.edit().putBoolean("twoTierEnabled_${tier.name}", enabled).apply()
        Log.i(TAG, "twoTierEnabled ${tier.name} = $enabled")
    }

    /** 该速度簇选中的模型 id（无记录或非法 → 回退到 [fallback]）。 */
    fun twoTierModelId(tier: SpeedTier, fallback: String?): String? {
        val saved = prefs.getString("twoTierModel_${tier.name}", null)
        return saved ?: fallback
    }

    /** 设置某速度簇选中的模型 id。 */
    fun setTwoTierModelId(tier: SpeedTier, id: String) {
        prefs.edit().putString("twoTierModel_${tier.name}", id).apply()
        Log.i(TAG, "twoTierModel ${tier.name} = $id")
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
        private const val KEY_MODEL_SOURCE = "model_source_preference"
        private const val KEY_MODEL_RESOLVED = "model_source_resolved"
        private const val KEY_DOWNLOAD_PROXY = "model_download_proxy"

        /**
         * ★默认 GitHub 代理前缀（实测可用）：`api.gitproxy.dev/github.com/<path>`
         * **前缀替换**为 `github.com/<path>`（Cloudflare 加速）。
         * 用户清空设置 = 直连 github.com。
         */
        const val DEFAULT_GITHUB_PROXY = "https://api.gitproxy.dev"

        const val DECK_SEC_DEFAULT = 18
        const val DECK_SEC_MIN = 9
        const val DECK_SEC_MAX = 36
        const val DECK_SEC_STEP = 3
    }
}
