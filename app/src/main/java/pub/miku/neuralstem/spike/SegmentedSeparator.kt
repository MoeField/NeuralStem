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
    private val vocalsBuffer: StemPcmRingBuffer,
    private val accompBuffer: StemPcmRingBuffer,
    private val separator: ChunkSeparator,
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
     * ★G-3fix（可选）：并行落盘。非 null 时，**写给 ring buffer 的同一份数据**
     * 同时 append 到这两个 WavWriter。
     *
     * 为何需要：edge 分离完成后要关 buffer（读端 EOF），若播放器此时还在读
     * `stem://` 就会误判 `STATE_ENDED` → 播放提前结束。落盘后上层可**无缝切到
     * 文件播放**（文件完整、可 seek 到 [startFrame] 之后任意位置）。
     *
     * 注意：落盘内容 = `[startFrame, 曲尾)`，**不包含** startFrame 之前的部分。
     */
    private val vocalsWriter: WavWriter? = null,
    private val accompWriter: WavWriter? = null,
    /**
     * ★取消钩子：每个块开始前调用一次，返回 true 则抛 [CancellationException] 中止。
     *
     * 为何需要：本类的 `run()` 是**同步阻塞**代码（在 `Dispatchers.IO` 跑），
     * Kotlin 协程的 `job.cancel()` 只标记状态、不会中断阻塞调用 →
     * 用户点“取消分离”要等到**整首跑完**才生效。每块前查一次即可快速响应。
     */
    private val isCancelled: (() -> Boolean)? = null,
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
                vocalsBuffer.append(v, 0, mergeLen)
                accompBuffer.append(a, 0, mergeLen)
                vocalsWriter?.appendRegion(v, 0, mergeLen)
                accompWriter?.appendRegion(a, 0, mergeLen)
                pendFrames = 0
            }

            // ---- 其余 ----
            if (isLast) {
                appendRaw(res.vocals, res.accompaniment, mergeLen, lFrames - mergeLen)
            } else {
                val settled = hop - mergeLen
                if (settled > 0) appendRaw(res.vocals, res.accompaniment, mergeLen, settled)
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
            vocalsFrames = vocalsBuffer.totalWrittenFrames,
            accompFrames = accompBuffer.totalWrittenFrames,
            chunks = plan.size,
        )
    }

    private fun appendRaw(v: FloatArray, a: FloatArray, offsetFrames: Int, count: Int) {
        if (count <= 0) return
        vocalsBuffer.append(v, offsetFrames, count)
        accompBuffer.append(a, offsetFrames, count)
        // ★G-3fix：并行落盘（与 buffer 写入同一份数据，保证内容一致）。
        vocalsWriter?.appendRegion(v, offsetFrames, count)
        accompWriter?.appendRegion(a, offsetFrames, count)
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
