package pub.miku.neuralstem.spike

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * ★G-3：“边分离边播”的**分段分离器**。
 *
 * ## 与 [StreamingSeparator] 的关系
 * 两者共用同一套「分块 + 重叠 + 线性交叉淡化 + 归一化」几何（权重公式与舍入规则一致）。
 * 区别只在**输出去向**：
 * - [StreamingSeparator] → 写 `WavWriter`（文件，分离完才能播）；
 * - 本类 → 写 [StemPcmRingBuffer]（内存，**首段完成即可播**）。
 *
 * ## 关键能力
 * - **从任意位置起分离**：[startFrame]（= 当前播放位置），之前的部分不处理。
 * - **变长递增块**：[chunkSchedule]（如 4s→8s→16s→30s），首段快出结果。
 * - **首段回调**：[onFirstSegmentReady]（第一段写出后才触发 → 上层可切轨播放）。
 *
 * ## 不做的事（MVP）
 * - 不做 seek 到未分离区的等待/补齐（上层 clamp 到已分离末尾）。
 */
class SegmentedSeparator(
    private val reader: FrameSource,
    private val separator: ChunkSeparator,
    /**
     * ★Step 2：**输出回调**（取代直接持 buffer/writer）。
     *
     * 参数：`(vocals, accomp, frames, absFrame)` —— 两轨样本（交织 float）、
     * 本段帧数、以及它在**整曲中的绝对起始帧**。
     *
     * 为何抽象：本类不再关心"写到哪里"（ring buffer / 随机写文件 / 两者皆写），
     * 由上层决定 → 同一套分离逻辑可服务“边播”与“落盘”两种场景。
     */
    private val onWrite: (vocals: FloatArray, accomp: FloatArray, frames: Int, absFrame: Int) -> Unit,
    /** 从该绝对帧开始分离（含）。默认 0。 */
    private val startFrame: Int = 0,
    /** 重叠帧数，默认 0.5s @44.1kHz（G-3 决策 D-F）。 */
    private val overlapFrames: Int = DEFAULT_OVERLAP_FRAMES,
    /** 变长递增块序列（帧）。非空时使用；空则用 [chunkFrames]。 */
    private val chunkSchedule: List<Int>? = null,
    private val chunkFrames: Int = StreamingSeparator.DEFAULT_CHUNK_FRAMES,
    /** 进度回调：已完成帧 / 总帧（相对整个音频）。 */
    private val onProgress: ((doneFrames: Int, totalFrames: Int) -> Unit)? = null,
    /** 第一段写出后回调一次（供上层切换到“可播”）。 */
    private val onFirstSegmentReady: (() -> Unit)? = null,
    /**
     * ★取消钩子：每个块开始前调用一次，返回 true 则抛 [CancellationException] 中止。
     *
     * 为何需要：本类的 `run()` 是**同步阻塞**代码（在 `Dispatchers.IO` 跑），
     * Kotlin 协程的 `job.cancel()` 只标记状态、不会中断阻塞调用 →
     * 用户点“取消分离”要等到**整首跑完**才生效。每块前查一次即可快速响应。
     */
    private val isCancelled: (() -> Boolean)? = null,
    /**
     * ★G-3-ux2：**包络回调**（波形实时更新用）。
     *
     * 参数：`(stemIndex, absFrame, min, max, pointCount)` —— stemIndex 0=vocals 1=accomp；
     * min/max 长度 = pointCount，每个点对应 20ms（= `sr / 50` 帧）。
     *
     * 为何在分离器里算：分离时 PCM 本就在手上，顺手算 min/max 几乎零额外成本；
     * 而重提取（读文件重新解码）会重复耗时，且文件未写完时读到的是静音。
     *
     * 回调在**每个块完成后**触发一次，带上本块覆盖的包络区间。
     */
    private val onEnvelope: ((stemIndex: Int, absFrame: Int, min: FloatArray, max: FloatArray, pointCount: Int) -> Unit)? = null,
) {
    /** 统计。 */
    data class Stats(
        val startFrame: Int,
        val totalFrames: Int,
        val vocalsFrames: Long,
        val accompFrames: Long,
        val chunks: Int,
    )

    private data class Plan(val start: Int, val len: Int, val hop: Int)

    fun run(): Stats {
        val channels = reader.channels
        val sampleRate = reader.sampleRate
        val totalFrames = reader.totalFrames
        val from = startFrame.coerceIn(0, totalFrames)

        val plan = buildPlan(from, totalFrames)
        require(overlapFrames > StreamingSeparator.MAX_DROPPED_FRAMES_PER_CALL) {
            "overlapFrames($overlapFrames) 必须 > ${StreamingSeparator.MAX_DROPPED_FRAMES_PER_CALL}"
        }
        if (plan.size > 1) {
            plan.forEachIndexed { i, c ->
                val isLast = i == plan.lastIndex
                require(c.len > 0) { "chunk #$i len 必须 > 0" }
                // 非尾块需 len >= 2*overlap（body 单覆盖前提）。
                if (!isLast) {
                    require(c.len >= 2 * overlapFrames) {
                        "chunk #$i len(${c.len}) 必须 >= 2*overlap($overlapFrames)"
                    }
                }
            }
        }

        Log.i(
            TAG,
            "segmented: from=$from total=$totalFrames ch=$channels sr=$sampleRate " +
                "overlap=$overlapFrames chunks=${plan.size} lens=${plan.take(6).map { it.len }}",
        )

        // pending 尾（与 StreamingSeparator 同索引约定：下标 0 = 下一块起点）。
        val pendV = FloatArray(overlapFrames * channels)
        val pendA = FloatArray(overlapFrames * channels)
        val pendW = FloatArray(overlapFrames)
        var pendFrames = 0

        var firstEmitted = false
        // ★Step 2：统计实际写出的帧数（两轨等长，共用一个计数）。
        var writtenFrames = 0L
        plan.forEachIndexed { idx, c ->
            // ★取消支持：`run()` 是同步阻塞代码（在 Dispatchers.IO 里跑），
            //   协程的 `job.cancel()` 只能标记状态，**无法中断正在执行的阻塞调用**。
            //   因此每个块开始前主动查一次 —— 否则用户点「取消分离」要等到整首跑完才生效。
            if (isCancelled != null && isCancelled()) {
                throw CancellationException("segmented separation cancelled at chunk #$idx")
            }
            val isFirst = idx == 0
            val isLast = idx == plan.lastIndex
            val hop = c.hop
            val block = reader.readFrames(c.start, c.len)
            require(block.isNotEmpty()) { "empty read at ${c.start}" }

            val res = separator.separate(block, channels, sampleRate)
            val lFrames = res.vocals.size / channels
            require(lFrames == res.accompaniment.size / channels) { "两轨长度不一致" }
            if (lFrames <= 0) return@forEachIndexed
            if (!isLast) {
                require(lFrames >= hop) {
                    "chunk #$idx 输出 $lFrames < hop $hop → 块间留空洞（增大 overlap）"
                }
            }

            // ---- head 区：与本块 fadeIn 加权合并 ----
            val headLen = minOf(overlapFrames, lFrames)
            val mergeLen = maxOf(headLen, pendFrames)
            if (mergeLen > 0) {
                val v = FloatArray(mergeLen * channels)
                val a = FloatArray(mergeLen * channels)
                for (f in 0 until mergeLen) {
                    val inChunk = f < headLen
                    val inPend = f < pendFrames
                    val w = if (inChunk) weightAt(f, lFrames, isFirst, isLast, overlapFrames) else 0f
                    val wp = if (inPend) pendW[f] else 0f
                    val sumW = w + wp
                    if (sumW <= 1e-6f) continue
                    val dst = f * channels
                    for (ch in 0 until channels) {
                        val src = f * channels + ch
                        var sv = 0f
                        var sa = 0f
                        if (inChunk) { sv += res.vocals[src] * w; sa += res.accompaniment[src] * w }
                        if (inPend) { sv += pendV[src]; sa += pendA[src] }
                        v[dst + ch] = sv / sumW
                        a[dst + ch] = sa / sumW
                    }
                }
                // ★Step 2：统一走输出回调（绝对帧 = c.start，即本块起始）。
                onWrite(v, a, mergeLen, c.start)
                writtenFrames += mergeLen
                pendFrames = 0
            }

            // ---- 其余 ----
            if (isLast) {
                writtenFrames += appendRaw(res.vocals, res.accompaniment, mergeLen, lFrames - mergeLen, c.start + mergeLen)
            } else {
                val settled = hop - mergeLen
                if (settled > 0) {
                    writtenFrames += appendRaw(res.vocals, res.accompaniment, mergeLen, settled, c.start + mergeLen)
                }
                // tail 暂存（加权）。
                val count = lFrames - hop
                require(count in 0..overlapFrames) { "pending 容量不足 count=$count" }
                for (i in 0 until count) {
                    val w = weightAt(hop + i, lFrames, isFirst, isLast, overlapFrames)
                    val src = (hop + i) * channels
                    val dst = i * channels
                    for (ch in 0 until channels) {
                        pendV[dst + ch] = res.vocals[src + ch] * w
                        pendA[dst + ch] = res.accompaniment[src + ch] * w
                    }
                    pendW[i] = w
                }
                pendFrames = count
            }

            onProgress?.invoke(c.start + lFrames, totalFrames)
            if (!firstEmitted) {
                firstEmitted = true
                onFirstSegmentReady?.invoke()
            }
        }

        check(pendFrames == 0) { "结束时仍残留 $pendFrames 帧" }

        return Stats(
            startFrame = from,
            totalFrames = totalFrames,
            vocalsFrames = writtenFrames,
            accompFrames = writtenFrames,
            chunks = plan.size,
        )
    }

    /** @return 实际写出的帧数（0 表示没写）。 */
    private fun appendRaw(v: FloatArray, a: FloatArray, offsetFrames: Int, count: Int, absFrame: Int): Int {
        if (count <= 0) return 0
        val ch = reader.channels
        // ★Step 2：必要时先切片，保证回调收到的是「从 0 开始的连续段」。
        val sv: FloatArray
        val sa: FloatArray
        if (offsetFrames == 0 && count == v.size / ch) {
            sv = v
            sa = a
        } else {
            sv = FloatArray(count * ch)
            sa = FloatArray(count * ch)
            System.arraycopy(v, offsetFrames * ch, sv, 0, count * ch)
            System.arraycopy(a, offsetFrames * ch, sa, 0, count * ch)
        }
        onWrite(sv, sa, count, absFrame)
        // ★G-3-ux2：顺带算包络（PCM 本就在手上，零额外解码）���
        emitEnvelope(sv, 0, count, absFrame)
        emitEnvelope(sa, 1, count, absFrame)
        return count
    }

    /**
     * ★G-3-ux2：把一段 PCM 转成包络点并回调。
     *
     * 包络点固定 20ms（= `sampleRate / 50` 帧），且**按绝对帧对齐**：
     * 第 p 个点覆盖 `[p*fpp, (p+1)*fpp)`，与 [WaveformExtractor.POINTS_PER_SECOND] 一致。
     */
    private fun emitEnvelope(samples: FloatArray, stemIndex: Int, frames: Int, absFrame: Int) {
        val cb = onEnvelope ?: return
        val ch = reader.channels
        val fpp = (reader.sampleRate / 50).coerceAtLeast(1)   // 每包的帧数
        // 找出本次要输出的包络点区间：floor(absFrame/fpp) .. ceil((absFrame+frames)/fpp)
        val p0 = absFrame / fpp
        val p1 = (absFrame + frames + fpp - 1) / fpp
        val n = p1 - p0
        if (n <= 0) return
        val mn = FloatArray(n)
        val mx = FloatArray(n)
        for (k in 0 until n) {
            val pt = p0 + k
            // 该点在整曲中的帧区间，与本次数据的交集。
            val ptStart = pt.toLong() * fpp
            val ptEnd = ptStart + fpp
            val segStart = maxOf(ptStart, absFrame.toLong())
            val segEnd = minOf(ptEnd, (absFrame + frames).toLong())
            if (segEnd <= segStart) {
                // 本点不在此段内（一般不会发生，仅边界）
                mn[k] = 0f; mx[k] = 0f; continue
            }
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            var i = ((segStart - absFrame) * ch).toInt()
            val end = ((segEnd - absFrame) * ch).toInt()
            while (i < end) {
                val x = samples[i]
                if (x < lo) lo = x
                if (x > hi) hi = x
                i += ch
            }
            mn[k] = if (lo == Float.MAX_VALUE) 0f else lo
            mx[k] = if (hi == -Float.MAX_VALUE) 0f else hi
        }
        cb(stemIndex, (p0 * fpp).toInt(), mn, mx, n)
    }

    /** 与 [StreamingSeparator] 完全一致的权重公式。 */
    private fun weightAt(f: Int, lFrames: Int, isFirst: Boolean, isLast: Boolean, overlap: Int): Float {
        var w = 1f
        if (!isFirst && f < overlap) w *= (f + 0.5f) / overlap
        if (!isLast && f >= lFrames - overlap) w *= (lFrames - 1 - f + 0.5f) / overlap
        return w
    }

    private fun buildPlan(from: Int, totalFrames: Int): List<Plan> {
        if (from >= totalFrames) return emptyList()
        val schedule = chunkSchedule
        if (schedule.isNullOrEmpty()) {
            val hop = chunkFrames - overlapFrames
            val plan = ArrayList<Plan>()
            var s = from
            while (s < totalFrames) {
                val len = minOf(chunkFrames, totalFrames - s)
                plan += Plan(s, len, hop)
                if (s + len >= totalFrames) break
                s += hop
            }
            return plan
        }
        val plan = ArrayList<Plan>()
        var s = from
        var i = 0
        while (s < totalFrames) {
            val len = minOf(schedule[i % schedule.size], totalFrames - s)
            plan += Plan(s, len, len - overlapFrames)
            if (s + len >= totalFrames) break
            val hop = len - overlapFrames
            require(hop > 0) { "变长块 hop<=0: len=$len overlap=$overlapFrames" }
            s += hop
            i++
        }
        return plan
    }

    companion object {
        private const val TAG = "SegmentedSep"
        /** 0.5s @44.1kHz —— G-3 决策（覆盖块边界失真）。 */
        const val DEFAULT_OVERLAP_FRAMES = 22_050

        /** G-3 默认递增块：4s→8s→16s→30s（帧）。 */
        fun defaultSchedule(sampleRate: Int): List<Int> {
            require(sampleRate > 0)
            return listOf(4, 8, 16, 30).map { it * sampleRate }
        }
    }
}
