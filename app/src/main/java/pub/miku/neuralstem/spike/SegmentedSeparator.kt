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
    /**
     * ★两阶段补算（2026-10-20）：**分离到该绝对帧就停（不含）**。
     *
     * 用途：阶段 1 已算完 [startFrame, 曲尾)，阶段 2 要补 [0, startFrame)。
     * 若无此参数，阶段 2 会从 0 一路算到曲尾 → **重复计算阶段 1 的区间**。
     *
     * 默认 -1 = 不限制（算到曲尾，旧行为）。
     */
    private val endFrame: Int = -1,
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
        // ★两阶段：endFrame 限制本轮分离的上界（阶段 2 补 [0, startFrame) 时用）。
        //   -1 / 超出范围 = 算到曲尾（旧行为）。
        val to = if (endFrame < 0) totalFrames else endFrame.coerceIn(from, totalFrames)

        val plan = buildPlan(from, to)
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

        // ★尾块不得短于 MIN_CHUNK_FRAMES（2026-10-26 真实崩溃修复，见下方注释）。
        //
        // ## 崩溃现场（真机 BF00A，喀秋莎 150s）
        // ```
        // segmented: from=6639816 total=6640128 chunks=1 lens=[312]
        // Abort message: 'terminating due to uncaught exception of type std::length_error: vector'
        //   #12 libsherpa-onnx-c-api.so (SherpaOnnxOfflineSourceSeparationProcess+188)
        //   #13 libneuralstem_spike.so (nativeSeparate+272)
        // ```
        //
        // ## 根因
        // sherpa 内部按 **512 跳长**做 STFT。312 帧 → 切出 **0 个频谱帧** →
        // 它去构造长度 0（或负数换算）的 vector → `std::length_error`。
        // 而上面那段 `require` **只在 `plan.size > 1` 时生效**，
        // 于是“只剩一个超小尾块”的情况被放过去了。
        //
        // ## 修法
        // 尾块过短就**并入前一块**（把前一块延长到曲尾），而不是单独成块。
        // 合并后只多算几百帧，代价远小于崩溃。
        val plan2 = normalizeTail(plan, totalFrames)

        Log.i(
            TAG,
            "segmented: from=$from total=$totalFrames ch=$channels sr=$sampleRate " +
                "overlap=$overlapFrames chunks=${plan2.size} lens=${plan2.take(6).map { it.len }}",
        )

        // pending 尾（与 StreamingSeparator 同索引约定：下标 0 = 下一块起点）。
        val pendV = FloatArray(overlapFrames * channels)
        val pendA = FloatArray(overlapFrames * channels)
        val pendW = FloatArray(overlapFrames)
        var pendFrames = 0

        var firstEmitted = false
        // ★Step 2：统计实际写出的帧数（两轨等长，共用一个计数）。
        var writtenFrames = 0L
        plan2.forEachIndexed { idx, c ->
            // ★取消支持：`run()` 是同步阻塞代码（在 Dispatchers.IO 里跑），
            //   协程的 `job.cancel()` 只能标记状态，**无法中断正在执行的阻塞调用**。
            //   因此每个块开始前主动查一次 —— 否则用户点「取消分离」要等到整首跑完才生效。
            if (isCancelled != null && isCancelled()) {
                throw CancellationException("segmented separation cancelled at chunk #$idx")
            }
            val isFirst = idx == 0
            val isLast = idx == plan2.lastIndex
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

            // ★两阶段：进度分母用本轮的**上界** to（不是整曲 totalFrames）。
            //   否则阶段 2（补 [0, startFrame)）的 done 永远追不上 totalFrames，
            //   进度条会卡在某个百分比。
            onProgress?.invoke(c.start + lFrames, to)
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

    /**
     * ★把过短的**尾块**并入前一块（2026-10-26 崩溃修复）。
     *
     * ## 为什么必须做
     * sherpa 内部按 **512 跳长**做 STFT。当尾块只有 312 帧（不到 1 个跳长）时，
     * 它切出 **0 个频谱帧** → 构造长度 0 / 负数换算的 vector →
     * `std::length_error` → **SIGABRT 直接闪退**（真机实测，喀秋莎 150s）。
     *
     * ## 为何不在 native 侧拦
     * `std::length_error` 是 C++ 异常，会直接 abort 进程，
     * JNI 层根本回不来（不行就无处可捕获）。必须在**送进去之前**就保证块够长。
     *
     * ## 阈值
     * [MIN_CHUNK_FRAMES] = 1 秒（44100 帧）。它 ≫ 512跳长（足够切出频谱帧），
     * 又远小于最小块 4s，不会误伤正常块。
     *
     * ## 合并方式
     * 把**前一块延长到曲尾**（`len` 变大），而不是删除尾块 ——
     * 否则会丢掉最后那几百帧音频。
     */
    private fun normalizeTail(plan: List<Plan>, totalFrames: Int): List<Plan> {
        if (plan.isEmpty()) return plan

        // ★只有一块且它就很小（真机正是这种情况：chunks=1 lens=[312]）
        //
        // 此时**无法合并**（没有“前一块”）。但也不能就这么送进去 —— 会 abort。
        // 办法：**向前扩张**起点，让这一块够长。
        // 多算几百毫秒音频，比闪退好得多；而且这段本来就要算（属于“补缺口”）。
        if (plan.size == 1) {
            val only = plan.first()
            if (only.len >= MIN_CHUNK_FRAMES) return plan
            val newStart = (only.start + only.len - MIN_CHUNK_FRAMES)
                .coerceAtLeast(0)
            // 仍不够（整首就短于 MIN）→ 只能报错，绝不送进去崩进程。
            val newLen = totalFrames - newStart
            if (newLen < MIN_CHUNK_FRAMES) {
                throw IllegalArgumentException(
                    "单块只有 ${only.len} 帧（< MIN_CHUNK_FRAMES=$MIN_CHUNK_FRAMES）：" +
                        "sherpa 会因切不出频谱帧而 std::length_error 闪退。" +
                        "start=${only.start} total=$totalFrames",
                )
            }
            Log.i(
                TAG,
                "normalizeTail: 单块只有 ${only.len} 帧（< $MIN_CHUNK_FRAMES）" +
                    "→ 向前扩张 start=${only.start}→$newStart，len→$newLen",
            )
            return listOf(Plan(newStart, newLen, newLen - overlapFrames))
        }

        val last = plan.last()
        if (last.len >= MIN_CHUNK_FRAMES) return plan

        // 多块：把最后两块合并（保留前一块的 start，长度延伸到曲尾）。
        val prev = plan[plan.size - 2]
        val mergedLen = totalFrames - prev.start
        val merged = Plan(prev.start, mergedLen, mergedLen - overlapFrames)
        Log.i(
            TAG,
            "normalizeTail: 尾块只有 ${last.len} 帧（< $MIN_CHUNK_FRAMES）→ 并入前一块，" +
                "len=${prev.len}→$mergedLen",
        )
        return plan.subList(0, plan.size - 2) + merged
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

        /**
         * ★单块最小帧数（1 秒 @44.1kHz）。
         *
         * ## 为何存在（2026-10-26 崩溃修复）
         * sherpa 内部按 **512 跳长**做 STFT。块短到不到一个跳长（实测尾块只有 312 帧）时
         * 切出 **0 个频谱帧** → 构造异常长度的 vector → `std::length_error` →
         * **SIGABRT 直接闪退**。这个值是“足够切出频谱帧”的安全下限。
         */
        const val MIN_CHUNK_FRAMES = 44_100

        /** G-3 默认递增块：4s→8s→16s→30s（帧）。 */
        fun defaultSchedule(sampleRate: Int): List<Int> {
            require(sampleRate > 0)
            return listOf(4, 8, 16, 30).map { it * sampleRate }
        }
    }
}
