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

    companion object {
        private const val TAG = "SettingsRepo"
        private const val PREFS = "settings"
        private const val KEY_DEFAULT_ENGINE = "default_engine_id"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_BENCH_PROMPTED = "benchmark_prompted"
    }
}
