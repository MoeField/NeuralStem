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
         * ## 判据演进（2026-10-25 OPPO 实测后修正）
         * 初版：`freqRatio(取 min) < 80%` → **在空闲时误报**（小核本来就低）。
         * 现版：**同时**满足以下两条才算可疑：
         * 1. **不在空闲**（大核 ≥ 40%）—— 否则本次没跑推理，数据本身无效而非“污染”
         * 2. 大核频率 < [FREQ_RATIO_SUSPECT]（80%）**或** 热状态 ≥ MODERATE
         *
         * 即：**有负载但大核被压** = 真降频。
         */
        val suspect: Boolean
            get() {
                if (idle) return false                    // 空闲：不算污染（但调用方应确保不在空闲采样）
                if (thermalStatus >= THERMAL_MODERATE) return true
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

    /** 热状态阈值：2 = `THERMAL_STATUS_MODERATE`。 */
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
     * 采集一次快照。
     *
     * 全部读取都是 `runCatching`（`/sys` 可能因 SELinux/权限不可读，不能因此崩溃）。
     */
    fun snapshot(powerManager: PowerManager?): Snapshot {
        val ratios = readClusterRatios()
        return Snapshot(
            thermalStatus = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    powerManager?.currentThermalStatus ?: -1
                } else {
                    -1   // API 29 以下无此 API
                }
            }.getOrDefault(-1),
            maxTempC = readMaxTempC(),
            freqRatio = ratios?.maxOrNull(),
            allRatios = ratios.orEmpty(),
        )
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

    /**
     * 每个 CPU 核心的 `当前频率 / 最高频率`（升序）。
     *
     * 用于识别三种形态（见 [Snapshot.suspect] 的判据）：
     * - **全部核心都很低** → 空闲（不是降频）
     * - **大核高、小核低** → 正常（小核本来低）
     * - **大核也低** → 真降频
     */
    internal fun readClusterRatios(): List<Float>? = runCatching {
        val cpuDir = File("/sys/devices/system/cpu")
        val cpus = cpuDir.listFiles { f -> Regex("cpu\\d+").matches(f.name) } ?: return null
        val ratios = cpus.mapNotNull { c ->
            val cur = runCatching {
                File(c, "cpufreq/scaling_cur_freq").readText().trim().toLong()
            }.getOrNull() ?: return@mapNotNull null
            val max = runCatching {
                File(c, "cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull() ?: return@mapNotNull null
            if (max <= 0L || cur <= 0L) null else (cur.toFloat() / max).coerceIn(0f, 1f)
        }
        if (ratios.isEmpty()) null else ratios.sorted()
    }.getOrNull()

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
     * 等待设备冷却到「不可疑」状态，最多等 [maxWaitMs]。
     *
     * 用法：分批跑 benchmark 时，每批之间调用一次，避免热降频污染。
     * 用**轮询**而不是 sleep 定长：冷却速度取决于环境温度与散热，定长必然要么浪费要么不够。
     *
     * @return true = 已冷却（可继续测）；false = 超时仍热（调用方应提示用户）
     */
    fun waitUntilCool(
        powerManager: PowerManager?,
        maxWaitMs: Long = 10 * 60 * 1000L,
        pollMs: Long = 15_000L,
        onProgress: ((elapsedMs: Long, snapshot: Snapshot) -> Unit)? = null,
    ): Boolean {
        val t0 = System.currentTimeMillis()
        while (true) {
            val s = snapshot(powerManager)
            val elapsed = System.currentTimeMillis() - t0
            onProgress?.invoke(elapsed, s)
            // ★冷却时 CPU 本来就空闲 → 频率低是正常的，**不能**用 suspect 判。
            //   只看热状态（冷却的目标就是让温度下来）。
            if (s.thermalStatus < THERMAL_MODERATE) {
                Log.i(TAG, "cooled after ${elapsed}ms (${s.describe()})")
                return true
            }
            if (elapsed >= maxWaitMs) {
                Log.w(TAG, "waitUntilCool timeout after ${elapsed}ms (${s.describe()})")
                return false
            }
            Thread.sleep(pollMs)
        }
    }
}
