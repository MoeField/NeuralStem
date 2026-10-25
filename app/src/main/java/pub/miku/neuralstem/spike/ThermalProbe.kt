package pub.miku.neuralstem.spike

import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File

/**
 * ★测量时的**热状态 / CPU 降频**探针（benchmark 数据可信度证据）。
 *
 * ## 为什么需要
 * 2026-10-24 在 BF00A 上连跑 18 个模型（LONG 模式，约 16 分钟），发现**后半段全部慢了 30–38%**：
 * ```
 * uvr-9703  历史 1.01× → 本次 0.67×  (−34%)
 * uvr-Inst_HQ_4 历史 0.42× → 本次 0.28×  (−34%)
 * ```
 * 事后查证：`Thermal Status = 2`（MODERATE）、CPU 温度 53.9°C、**小/中核频率被压到 25%**
 * （`scaling_cur_freq = 500MHz` vs `cpuinfo_max_freq = 2000MHz`）。
 *
 * ⇒ **热降频会把 RTF 压低 1/3 而不报错**。若不记录状态，事后**无法分辨**
 *    "这台机器就是慢" 还是 "刚才是热的" —— 本轮就踩了这个坑，只能凭经验猜。
 *
 * ## 做法
 * 每个模型测前/测后各采一次快照，产出 [Snapshot]：
 * - [thermalStatus]：`PowerManager.getCurrentThermalStatus()`（0=NONE … 6=SHUTDOWN）
 * - [maxTempC]：`/sys/class/thermal/thermal_zoneN/temp` 最大值（无权限时为 null）
 * - [freqRatio]：**大核**频率比（当前 / 最高）。推理主力是大核，它降才是真降。
 *   （最小值 = 最被压的那一簇；大核没降而小核降到 25% 时，平均值会掩盖问题）
 *
 * [Snapshot.suspect] 给出**判定**：本次测量是否可能被降频污染。
 */
object ThermalProbe {

    private const val TAG = "ThermalProbe"

    /**
     * 一次快照。
     *
     * @param thermalStatus `PowerManager.getCurrentThermalStatus()`；-1 = 读不到
     * @param maxTempC 最高温（°C）；null = 无权限读取
     * @param freqRatio 最慢簇的当前/最高频率比（1.0 = 满频）；null = 读不到
     */
    data class Snapshot(
        val thermalStatus: Int,
        val maxTempC: Float?,
        /** **大核**的频率比（当前 / 最高）。推理主力大核，它降才是真降。 */
        val freqRatio: Float?,
        /** 全部核心的频率比（升序）。用于识别“整体空闲”与明细排查。 */
        val allRatios: List<Float> = emptyList(),
    ) {
        /**
         * 全部核心是否都处于低频（= **空闲**，不是降频）。
         *
         * 判据：**大核**也 < [FREQ_RATIO_IDLE]（40%）。
         * 空闲时小核本来就在 25%–40%，所以不能只看小核。
         */
        val idle: Boolean
            get() = freqRatio != null && freqRatio < FREQ_RATIO_IDLE

        /**
         * 是否**可疑**（本次测量可能被降频污染）。
         *
         * ## 判据演进（三次迭代，全部由真机实测驱动）
         *
         * **初版**：`freqRatio(取 min) < 80%`
         * → OPPO 误报：`cpu0-7 = 32%~47%` 是**空闲**调度，小核本来就低。
         *
         * **二版**：空闲(大核<40%)不算；有负载且(热状态≥MODERATE 或 大核<80%)才算
         * → BF00A 冷却时卡死：热状态**粘滞**（CPU 已 37°C 仍报 MODERATE）。
         *
         * **三版（当前）**：**只用频率**。
         *
         * ## 为何热状态与温度都不能用（实测证据）
         * ```
         * BF00A 冷却 7 分钟后：
         *   Thermal Status: 2                  ← 状态机没回落（vivo ROM）
         *   dumpsys 的 Cached temps: 53.878    ← 过热时的旧快照
         *   dumpsys 的 HAL temps:    37.842    ← 真实温度，但应用拿不到
         * /sys/class/thermal/thermal_zoneN/temp → Permission denied（Android 16）
         * ```
         * ⇒ 应用**无法可靠读温度**；热状态**不反映当前状态**。只有频率是可靠的。
         *
         * ## 当前判据
         * - **空闲**（大核 < 40%）→ 不可疑（没跑推理，数据无效但不是“污染”）
         * - **有负载且大核 < 80%** → 可疑（真降频）
         */
        val suspect: Boolean
            get() {
                if (idle) return false                    // 空闲：不算污染（调用方应确保不在空闲采样）
                return freqRatio != null && freqRatio < FREQ_RATIO_SUSPECT
            }

        /** 人类可读（日志与 UI 展示）。 */
        fun describe(): String = buildString {
            append("thermal=").append(thermalName(thermalStatus))
            append(" temp=").append(maxTempC?.let { "%.1f°C".format(it) } ?: "n/a")
            append(" bigCore=").append(freqRatio?.let { "%.0f%%".format(it * 100) } ?: "n/a")
            if (idle) append(" (idle)")
            if (suspect) append(" ⚠️SUSPECT")
        }

        /** 紧凑型（写进 JSON 缓存）。 */
        fun toJsonKeys(): Triple<Int, Float?, Float?> = Triple(thermalStatus, maxTempC, freqRatio)
    }

    /** 热状态阈值：2 = `THERMAL_STATUS_MODERATE`。**仅用于展示**，不参与 suspect 判定。 */
    private const val THERMAL_MODERATE = 2

    /** 大核频率比低于此值 → 判为可疑（80%）。 */
    private const val FREQ_RATIO_SUSPECT = 0.80f

    /**
     * 大核频率比低于此值 → 判为空闲（40%）。
     *
     * 依据：OPPO MT6833 空载实测大核 32%–47%；跑推理时大核会顶到 ≥80%。
     * 取 40% 作分界（宁可把空闲当空闲，也不要误报降频）。
     */
    private const val FREQ_RATIO_IDLE = 0.40f

    /**
     * 冷却目标：大核频率比**回到**此值以上（92%）。
     *
     * 为何是 92%：降频恢复后大核几乎总是顶满（BF00A 实测 2200/2200 = 100%）。
     * 留 8% 容差是防调度器在满频附近小幅波动。
     *
     * ⚠️ **必须在有负载时才有意义** —— 空闲时大核只有 33%。
     * 所以 [waitUntilCool] 的策略是：**先确认回到满频，再看是否持续一段时间**。
     */
    private const val FREQ_RATIO_RECOVERED = 0.92f

    /**
     * 采集一次快照。
     *
     * 全部读取都是 `runCatching`（`/sys` 可能因 SELinux/权限不可读，不能因此崩溃）。
     */
    fun snapshot(powerManager: PowerManager?): Snapshot {
        val (bigRatio, all) = readFrequencyRatios()
        return Snapshot(
            thermalStatus = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    powerManager?.currentThermalStatus ?: -1
                } else {
                    -1   // API 29 以下无此 API
                }
            }.getOrDefault(-1),
            maxTempC = readMaxTempC(),
            freqRatio = bigRatio,
            allRatios = all,
        )
    }

    /**
     * 读频率比：返回 (`大核簇的频率比`, `全部核心的频率比（升序）`)。
     *
     * ## ★ 为何要按“峰频”分类而不是取 min/max（两次实测教训）
     *
     * **取 min**（初版）：OPPO 空闲时 cpu0-7 = 32%~47%，全是小核低频 → 误报。
     *
     * **取 max**（二版）：BF00A 热降频时 `cpu0/4 = 500MHz(25%)` 但 `cpu7 = 2200MHz(100%)`
     * → max 仍为 100%，**降频被完全掩盖**。
     *
     * **正解**：按 `cpuinfo_max_freq` 把核心分簇（同一峰频 = 同一簇），
     * **取峰频最高那一簇的均值**（真正的大核）。
     *
     * ## 为何不用「全部核心均值取小」（三版试过，过度敏感）
     * 2026-10-25 BF00A 实测：推理期间小核会正常降频（节能），
     * 导致全部核心均值只有 44% —— 但这**不是热降频**（空载时三核全满频）。
     * 用它作判据 → BF00A 全部数据被误标 SUSPECT。
     * ⇒ 只看大核簇：小核降是正常调度，大核降才是限频。
     *
     * ## ⚠️ 已知限制：BF00A 的 MTK 调度器会压大核
     * 实测 BF00A（MT6789）推理期间 `cpu6/7`（峰 2200MHz）也被压到 ~968MHz（44%），
     * 但**空载时立刻回到 100%**。这是 MTK 调度器的主动降频（非热），
     * 但**确实会让 RTF 变低** → 标 `SUSPECT` 是对的（数据参考价值降低）。
     *
     * ⇒ 后果：BF00A 上**每次**测量都会被标记。这不影响“簇结构”结论，
     * 但说明 BF00A 的**绝对值长期偏低**（历史数据 0.35× 可能是已经被压过的）。
     * 已知未修：真正的修法应该是“与本机历史基线对比”而不是绝对阈值。
     */
    private fun readFrequencyRatios(): Pair<Float?, List<Float>> {
        val raw = runCatching {
            val cpuDir = File("/sys/devices/system/cpu")
            val cpus = cpuDir.listFiles { f -> Regex("cpu\\d+").matches(f.name) } ?: return@runCatching null
            cpus.mapNotNull { c ->
                val cur = runCatching {
                    File(c, "cpufreq/scaling_cur_freq").readText().trim().toLong()
                }.getOrNull() ?: return@mapNotNull null
                val max = runCatching {
                    File(c, "cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }.getOrNull() ?: return@mapNotNull null
                if (max <= 0L || cur <= 0L) null else Triple(max, cur, cur.toFloat() / max)
            }
        }.getOrNull().orEmpty()

        if (raw.isEmpty()) return null to emptyList()

        // 峰频最高那一簇（真正的大核）— 推理主力，它降才是真降频
        val topPeak = raw.maxOf { it.first }
        val bigCluster = raw.filter { it.first == topPeak }.map { it.third }
        val bigAvg = bigCluster.average().toFloat()

        return bigAvg to raw.map { it.third }.sorted()
    }

    /** 读最高温度（°C）。`thermal_zoneN/temp` 单位是**毫摄氏度**。 */
    private fun readMaxTempC(): Float? = runCatching {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?: return null
        val temps = zones.mapNotNull { z ->
            runCatching { File(z, "temp").readText().trim().toFloat() }.getOrNull()
        }.filter { it > 0f && it < 200_000f }     // 过滤非法值
        if (temps.isEmpty()) null else temps.max() / 1000f
    }.getOrNull()

    /**
     * 读 **最快那一簇**（大核）的频率比（当前 / 最高）。
     *
     * ## 为何用 max 而不是 min（首次实现用 min，实测误报）
     * 2026-10-25 在 OPPO（天玑 MT6833）上首次用 min：
     * ```
     * thermal=NONE freq=25% ⚠️SUSPECT      ← 误报！
     * 实际：cpu0-7 = 32%~47%（空闲调度，没负载）
     * ```
     * **空闲时小核本来就在低频**，min 把它当成降频 → 几乎每次都误判。
     *
     * ## 为何是 max
     * ONNX 推理的主力是**大核**（ORT 线程池优先大核）。大核被压才是真的降频。
     * BF00A 热降频实测：`cpu0/4 = 500MHz(25%)` 而 `cpu7 = 2200MHz(100%)` ——
     * 此时 max 看不出问题（但那是小核降而大核没降，属另一种形态）。
     * ⇒ **单看任一统计量都有盲区**，所以另给 [Snapshot.allRatios] 保留每核明细。
     */

    /** 热状态名（与 `PowerManager.THERMAL_STATUS_*` 对齐）。 */
    fun thermalName(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }

    /**
     * 等待设备冷却到可继续测量的状态，最多等 [maxWaitMs]。
     *
     * 用法：分批跑 benchmark 时，每批之间调用一次，避免热降频污染。
     *
     * ## 判据：**做一次短促探测负载，看大核能不能顶满**
     *
     * ### 为何不能用热状态（BF00A 实测）
     * 冷却 7 分钟后：
     * ```
     * Thermal Status: 2                  ← 状态机没回落（vivo ROM 粘滞）
     * HAL 实测 CPU:   37.842°C           ← 实际已经凉了
     * ```
     * 死等热状态 → 白等到 10 分钟超时。
     *
     * ### 为何不能只看空闲频率
     * 冷却时 CPU 本来就空闲（大核 33%）—— **空闲时无法分辨“凉了”和“在降频”**。
     *
     * ### 为何不能用温度
     * `/sys/class/thermal/...` 在 Android 15+ 对应用 **Permission denied**（BF00A 实测）；
     * HAL 的实时温度普通应用拿不到。
     *
     * ### 所以：探测负载
     * 每轮先跑 [PROBE_BUSY_MS] 的纯 CPU 活（把大核逼到满频），再采频率：
     * - 大核 ≥ [FREQ_RATIO_RECOVERED] → **凉了**（能顶满 = 没被限频）
     * - 否则 → 还在降频，继续等
     *
     * 探测本身只跑 300ms，对温度影响极小。
     *
     * @return true = 可继续测；false = 超时仍限频（调用方应提示用户，数据仍会标记可疑）
     */
    fun waitUntilCool(
        powerManager: PowerManager?,
        maxWaitMs: Long = 10 * 60 * 1000L,
        pollMs: Long = 15_000L,
        onProgress: ((elapsedMs: Long, snapshot: Snapshot) -> Unit)? = null,
    ): Boolean {
        val t0 = System.currentTimeMillis()
        var first = true
        while (true) {
            // 首轮不睡：刚测完就是热的，立刻探测没意义→ 先让它喘口气
            if (!first) Thread.sleep(pollMs)
            first = false

            // ★探测负载**期间连续采样**（而非只采一次）
            val s = probeUnderLoad(powerManager)
            val elapsed = System.currentTimeMillis() - t0
            onProgress?.invoke(elapsed, s)

            // ★判据：负载态下大核能顶满 = 凉了
            val recovered = !s.idle &&
                (s.freqRatio ?: 0f) >= FREQ_RATIO_RECOVERED
            Log.i(
                TAG,
                "probe: ratio=${s.freqRatio} idle=${s.idle} recovered=$recovered " +
                    "all=${s.allRatios.map { "%.2f".format(it) }}",
            )
            if (recovered) {
                Log.i(TAG, "cooled after ${elapsed}ms (${s.describe()}) — 负载下大核能顶满")
                return true
            }
            if (elapsed >= maxWaitMs) {
                Log.w(
                    TAG,
                    "waitUntilCool timeout after ${elapsed}ms (${s.describe()}) " +
                        "— 仍在限频，继续测但数据已标记可疑",
                )
                return false
            }
        }
    }

    /**
     * 在**负载期间**采样：跑一段 CPU 活，同时反复采样，取**最大**频率比。
     *
     * ## 为何要多次采样
     * 2026-10-25 BF00A 实测：探测 300ms 只采一次时，相邻两轮会出现
     * `bigCore=100%` 与 `bigCore=33% (idle)` 交替 —— 采样时机恰好碰到
     * 调度器还没把频率推上去（或已经降下来）。单次采样**不可靠**。
     *
     * ## 为何取 max
     * 我们要回答的是“在降频吗？”，即“**能否**顶到高频”。
     * 只要有一次达到满频，就说明没被限频；取 max 正好表达这个语义。
     */
    private fun probeUnderLoad(powerManager: PowerManager?): Snapshot {
        val deadline = System.currentTimeMillis() + PROBE_BUSY_MS
        var best: Snapshot? = null
        var sink = 0.0
        var x = 1.0
        while (System.currentTimeMillis() < deadline) {
            // 跑一小段计算 → 采样 → 记录最大值
            repeat(20_000) { x = x * 1.0000001 + 0.0000001 }
            sink = x
            val s = snapshot(powerManager)
            if (best == null || (s.freqRatio ?: 0f) > (best.freqRatio ?: 0f)) best = s
        }
        if (sink.isInfinite()) Log.w(TAG, "spin sink infinite")   // 防优化
        return best ?: snapshot(powerManager)
    }

    /**
     * 探测负载时长（ms）。
     *
     * 300ms 足够让调度器把大核推上去（实测 OPPO/BF00A 都在 ~100ms 内到位），
     * 又短到几乎不影响温度。
     */
    private const val PROBE_BUSY_MS = 300L
}
