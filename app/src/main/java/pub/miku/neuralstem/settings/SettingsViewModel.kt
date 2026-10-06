package pub.miku.neuralstem.settings

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pub.miku.neuralstem.spike.DeviceBenchmark
import pub.miku.neuralstem.spike.ModelRegistry
import pub.miku.neuralstem.spike.ModelSpec
import pub.miku.neuralstem.spike.SherpaSourceSeparation
import java.io.File

/**
 * 设置页 VM(阶段 M2 任务 E)。
 *
 * 职责:
 * - 持有设备 benchmark 结果(来自 [DeviceBenchmark]，含缓存)
 * - 按 RTF 把模型分成三档(⚡ 实时 / ⏱ 较慢 / 🐢 很慢)
 * - 默认引擎选择(仅允许选"实时可用"档)
 * - EQ 开关(转发给播放侧)
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    /** 一个分组。 */
    enum class Tier(val title: String) { REALTIME("⚡ 实时可用"), SLOW("⏱ 较慢"), VERY_SLOW("🐢 很慢") }

    data class ModelRow(
        val spec: ModelSpec,
        val rtf: Float?,
        val ms: Long?,
        val installed: Boolean,
    ) {
        val rtfText: String get() = rtf?.let { "%.2f×".format(it) } ?: "—"
    }

    data class UiState(
        val grouped: Map<Tier, List<ModelRow>> = emptyMap(),
        val benchmarking: Boolean = false,
        /** 是否已探测过(有缓存或刚跑完) */
        val benchmarked: Boolean = false,
        val defaultEngineId: String = ModelRegistry.DEFAULT.id,
        /** "实时可用"档里可选作默认的 id 集合 */
        val selectableIds: Set<String> = emptySet(),
        val eqEnabled: Boolean = false,
        // ★G-1a：benchmark 进度/半态
        /** 进行中的进度（待测总数；0 = 未知/未在跑） */
        val benchTotal: Int = 0,
        /** 已完成数（含跳过） */
        val benchDone: Int = 0,
        /** 当前正在测的模型 id（无则 null） */
        val benchCurrentId: String? = null,
        /** 已安装但未测的模型数（>0 → 可“继续检测”） */
        val installedPending: Int = 0,
    )

    private val bench = DeviceBenchmark(app)
    private val settings = SettingsRepository(app)

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 实时门槛:RTF ≥ 2× 才算"实时可用"(留余量，见计划 Q1)。 */
    private val realtimeThreshold = 2f

    init {
        // ★G-2a：若 CPU 精确命中内置表且本机无缓存 → 免跑直接填（Q3：仅精确命中）。
        bench.seedFromBuiltinIfExact()
        // 若已有缓存（实测或免跑），直接展示
        refresh()
    }

    /** 重新计算分组(读缓存)。 */
    fun refresh() {
        _state.value = buildState()
    }

    /**
     * 跑 benchmark(短/长)，完成后刷新。
     * @param long true = LONG(3 轮)；false = SHORT(1 轮)
     * @param resume ★G-1a：true = 从缓存续测（跳过已测）。
     */
    fun runBenchmark(long: Boolean = false, resume: Boolean = false) {
        if (_state.value.benchmarking) return
        _state.value = _state.value.copy(
            benchmarking = true, benchTotal = 0, benchDone = 0, benchCurrentId = null,
        )
        viewModelScope.launch {
            try {
                val mode = if (long) DeviceBenchmark.Mode.LONG else DeviceBenchmark.Mode.SHORT
                val r = bench.run(mode, resume = resume) { done, total, id ->
                    // ★G-1a：进度回写（MutableStateFlow 线程安全，可在 Default 线程直接写）。
                    _state.value = _state.value.copy(
                        benchTotal = total, benchDone = done, benchCurrentId = id,
                    )
                }
                Log.i(TAG, "benchmark done: ${r.mapValues { "%.2f".format(it.value.rtf) }}")
            } catch (t: Throwable) {
                Log.e(TAG, "benchmark failed", t)
            } finally {
                _state.value = buildState().copy(benchmarking = false)
            }
        }
    }

    /** 重跑前失效缓存。 */
    fun invalidateAndRun(long: Boolean = false) {
        bench.invalidate()
        runBenchmark(long)
    }

    fun setDefaultEngine(id: String) {
        // 只允许选"实时可用"档
        if (id !in _state.value.selectableIds) return
        settings.defaultEngineId = id
        _state.value = _state.value.copy(defaultEngineId = id)
    }

    fun setEqEnabled(on: Boolean) {
        settings.eqEnabled = on
        _state.value = _state.value.copy(eqEnabled = on)
    }

    /** 标记首启弹窗已询问过。 */
    fun markBenchmarkPrompted() {
        settings.benchmarkPrompted = true
    }

    val wasBenchmarkPrompted: Boolean get() = settings.benchmarkPrompted

    // ---------------- 内部 ----------------

    private fun buildState(): UiState {
        val cached = bench.cached()
        val installedIds = installedModelIds()
        val groups = linkedMapOf(
            Tier.REALTIME to mutableListOf<ModelRow>(),
            Tier.SLOW to mutableListOf<ModelRow>(),
            Tier.VERY_SLOW to mutableListOf<ModelRow>(),
        )
        val selectable = mutableSetOf<String>()
        for (spec in ModelRegistry.ALL) {
            val b = cached[spec.id]
            val installed = spec.id in installedIds
            val row = ModelRow(spec, b?.rtf, b?.ms, installed)
            val tier = when {
                b == null -> Tier.VERY_SLOW          // 未测 → 先归"很慢"(保守)
                b.rtf >= realtimeThreshold -> Tier.REALTIME
                b.rtf >= 1f -> Tier.SLOW
                else -> Tier.VERY_SLOW
            }
            groups[tier]!!.add(row)
            if (tier == Tier.REALTIME && installed) selectable.add(spec.id)
        }
        return UiState(
            grouped = groups,
            benchmarked = cached.isNotEmpty(),
            defaultEngineId = settings.defaultEngineId,
            selectableIds = selectable,
            eqEnabled = settings.eqEnabled,
            // ★G-1c：已安装但未测的数量（>0 → 可“继续检测”）
            installedPending = installedIds.count { it !in cached.keys },
        )
    }

    /** 扫模型目录，判断哪些模型已安装。 */
    private fun installedModelIds(): Set<String> {
        val app = getApplication<Application>()
        val ids = mutableSetOf<String>()
        for (spec in ModelRegistry.ALL) {
            val dir = if (spec.engine == SherpaSourceSeparation.MODE_UVR) {
                File(app.filesDir, "uvr")
            } else {
                File(app.filesDir, "sherpa")
            }
            if (File(dir, spec.fileName).exists()) ids.add(spec.id)
        }
        return ids
    }

    /** 设备描述(设置页顶部展示)。 */
    fun deviceSummary(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        return "${Build.MANUFACTURER} ${Build.MODEL} · $soc · Android ${Build.VERSION.RELEASE}"
    }

    companion object {
        private const val TAG = "SettingsVm"
    }
}
