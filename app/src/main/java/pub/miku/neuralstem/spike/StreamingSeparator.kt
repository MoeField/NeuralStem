package pub.miku.neuralstem.spike

import android.util.Log

/**
 * 把「音频块 → 分离结果」这一步抽象出来,便于注入假实现做单元测试
 * (真实实现 [SherpaSourceSeparation] 需要 native 库与 ONNX 模型,单测里跑不了)。
 */
fun interface ChunkSeparator {
    fun separate(block: FloatArray, channels: Int, sampleRate: Int): SherpaSourceSeparation.SeparationResult
}

/**
 * 分块流式音轨分离(Task 0e —— 长音频的唯一出路)。
 *
 * =========================== 为什么必须在这一层分块 ===========================
 * 读 sherpa-onnx 上游 `offline-source-separation-spleeter-impl.h` 确认:
 * 它对传入波形做**整段 STFT**,把全部帧塞进**一个** ONNX 张量
 * `{2, num_frames/512, 512, 1024}` 一次性 forward,源码内**没有**音频块循环。
 * native 侧 `Eigen::VectorXf real/imag(2 * num_frames * 1024)` 随时长线性增长
 * (5:14 全曲 ≈ 2×110 MB)。所以「喂整曲」在两端都会爆:
 *   - Kotlin 堆:27.7M float ≈ 110 MB(API 25 模拟器堆仅 48 MB)→ 已实测 OOM
 *   - native 堆:Eigen 缓冲 ≈ 2×110 MB
 * 结论:**分块必须由 App 层完成**,这是本类存在的理由。
 * =============================================================================
 *
 * ## 关键约束 1:块输出长度 ≠ 块输入长度
 *
 * 实测(Task 0d,26.1s 输入 1151115 帧)输出 1150976 帧,差 139 帧。
 * 对该数据推导可**精确**拟合出 sherpa 的单次调用规则:
 * ```
 *   N_out = N_in - (N_in mod 1024)     ← 与实测完全吻合(1151115 - 139 = 1150976)
 * ```
 * 即**每次调用末尾最多丢 1 个 hop(1023 帧 ≈ 23.2ms)**。因此:
 *   1. 交叉淡化窗口只需 > 1 hop 覆盖全部损失;`overlap=4096` 留了 4× 余量。
 *   2. 被丢掉的尾部帧由**下一块**补上:`hop = chunk - overlap` 且 `L > hop` 时,
 *      下一块覆盖起点必然早于本块输出终点,故不留空洞(见 [requireChunkGeometry])。
 *
 * ## 关键约束 2:`chunk > 2 * overlap`(正确性前提,非优化)
 *
 * 本类把每块划分成三区(绝对帧坐标,`hop = chunk - overlap`):
 * ```
 *   head   [s, s+overlap)        与**前一块**的尾巴重叠 → 需加权合并
 *   body   [s+overlap, s+hop)    只被本块覆盖          → 可直写原始样本
 *   tail   [s+hop, s+L)          与**后一块**的头重叠  → 存入 pending
 * ```
 * **body 区只被本块覆盖**这一性质是零拷贝直写的依据,而它成立的充要条件是
 * `overlap <= hop`,即 `chunk >= 2*overlap`:
 *   - 前一块最多覆盖到 `s_prev + chunk = s + overlap`(不含),故不越入 body;
 *   - 后一块从 `s + hop` 才开始。
 * 若 `chunk < 2*overlap`,三区会互相穿插(一帧被 3 块覆盖),上述划分与权重
 * 归一化都不再成立 —— 故 [requireChunkGeometry] 直接拒绝,而不是产出错误音频。
 *
 * ## 拼接:为什么**不需要**回写已落盘数据
 *
 * 早期设计计划「块 N 落盘后,用 seek 回写重叠区做交叉淡化」。实际实现中,
 * 每块的 head 区是**先算好归一化结果再顺序写出**的,落盘顺序天然单调递增,
 * 因此完全不需要回写(`WavWriter` 也只保留顺序追加)。
 *
 * ## 权重与归一化
 *
 * 权重 `w(f) = fadeIn(f) * fadeOut(f)`,两端各为线性斜坡 `(i+0.5)/overlap`:
 * ```
 *   fadeIn(f)  = f < overlap          ? (f+0.5)/overlap        : 1   (首块恒 1)
 *   fadeOut(f) = f >= L-overlap       ? (L-1-f+0.5)/overlap    : 1   (尾块恒 1)
 * ```
 * 输出统一按 `Σ(w·x)/Σw` 求值(**加权平均**)。
 *
 * ⚠️ 注意:`Σw` 并不恰好等于 1 —— 因为真正的共享区宽度是
 * `L - hop`(而非 `overlap`),两者不等时权重和会偏离 1。
 * **但这不影响正确性**:归一化后 `Σ(w·x)/Σw` 是一个真加权平均,
 * 当所有贡献方给出相同值时它恒等于该值 —— 这正是「单覆盖区可直写原始样本」
 * 以及「恒等分离器可逐样本还原」两条性质成立的原因(均由单测验证)。
 * 归一化也让平滑性不依赖于斜坡长度是否精确互补。
 *
 * ## 内存(guide N2)
 *
 * 每块瞬时驻留:1 个输入块 + 1 份 [SherpaSourceSeparation.separate] 内部 planar
 * + 2 个 stem 输出(每份 ≈ `chunk*channels*4B`)。`pending` 尾巴另占
 * ≈ `overlap*channels*4B * 2 + overlap*4B` ≈ **147KB**,与歌曲总长无关。
 * 实测块长与 Java 堆(48MB)的对照见 Task 0e 记录:
 *   - 10s → 峰值 ≈ 15.9 MB ✅
 *   - **15s → 峰值 ≈ 23.8 MB ✅(默认值,余量 26.5MB)**
 *   - 30s → 峰值 ≈ 47.6 MB ️ 仅剩 2.7MB,叠加缓冲后必 OOM
 *   - 60s → ≈ 95.3 MB ❌
 */
class StreamingSeparator(
    /** Task 4.1:从具体的 WavReader 改为 [FrameSource] 接口,使 mp3/flac 也能分离 */
    private val reader: FrameSource,
    private val vocalsWriter: WavWriter,
    private val accompWriter: WavWriter,
    private val separator: ChunkSeparator,
    /**
     * 每块帧数。默认 15s @44.1kHz。
     *
     * 为何不是 30s:30s 块在 48MB 堆上峰值已达 47.6MB(余量 2.7MB),
     * 加上归一化缓冲必然 OOM。15s 余量 26.5MB,是当前堆约束下的安全上限。
     * E4 会实测 10s/15s 对比后定稿。
     */
    private val chunkFrames: Int = DEFAULT_CHUNK_FRAMES,
    /** 相邻块重叠帧数,必须 > 1024(1 个 hop)且 < chunk/2。默认 4096 ≈ 93ms。 */
    private val overlapFrames: Int = DEFAULT_OVERLAP_FRAMES,
    /**
     * ★G-3：变长递增分块序列（帧）。空 = 固定块（旧行为，[chunkFrames] 生效）。
     * 非空时按序列循环推进（例：`[4s,8s,16s,30s]` 帧数）。
     */
    private val chunkSchedule: List<Int>? = null,
    /** 块级进度回调(已完成帧 / 总帧),供 F3.2 进度条使用。 */
    private val onProgress: ((doneFrames: Int, totalFrames: Int) -> Unit)? = null,
) {

    /**
     * ★G-3：一块的分块计划。
     * @param start 起始绝对帧
     * @param len 本块读取帧数（变长时逐块不同）
     * @param hop 本块到下一块的推进量 = [len] - overlap（每块自己的 hop）
     */
    private data class ChunkPlan(val start: Int, val len: Int, val hop: Int)

    /**
     * ★G-3：构造分块计划。
     * - [chunkSchedule] 为空 → **固定块**（旧行为）：每块 [chunkFrames]、hop 恒定。
     * - 非空 → **变长递增**：按序列循环推进（`hop_i = len_i - overlap`）。
     *
     * 尾块约束：尾块长 ∈ (overlap, len]，且 `start + len >= total` 时收尾。
     */
    private fun buildPlan(totalFrames: Int): List<ChunkPlan> {
        if (totalFrames <= 0) return emptyList()
        val schedule = chunkSchedule
        if (schedule.isNullOrEmpty()) {
            // 固定块（旧路径，行为完全不变）。
            val hop = chunkFrames - overlapFrames
            val starts = chunkStarts(totalFrames, chunkFrames, hop)
            return starts.map { s ->
                ChunkPlan(s, minOf(chunkFrames, totalFrames - s), hop)
            }
        }
        // 变长递增块。
        val plan = ArrayList<ChunkPlan>()
        var start = 0
        var i = 0
        while (start < totalFrames) {
            val len = minOf(schedule[i % schedule.size], totalFrames - start)
            val hop = len - overlapFrames
            plan += ChunkPlan(start, len, hop)
            // 若本块已覆盖到末尾，结束。
            if (start + len >= totalFrames) break
            // 防御：hop 必须 > 0（由几何约束保证 len >= 2*overlap）。
            require(hop > 0) { "变长块 hop <= 0：len=$len overlap=$overlapFrames" }
            start += hop
            i++
        }
        return plan
    }

    /** 分块统计,用于 E4/E5 记录与决策。 */
    data class Stats(
        val inputFrames: Int,
        val vocalsFrames: Int,
        val accompFrames: Int,
        val chunks: Int,
        /** 每块实际输出帧数 —— 用来验证 `N_out = N_in - (N_in mod 1024)` 这条规则。 */
        val chunkOutputLengths: List<Int>,
    ) {
        /** 末尾未能补上的帧(理论上 < 1 个 hop)。 */
        val tailLossFrames: Int get() = inputFrames - vocalsFrames
    }

    /**
     * 执行流式分离,边推边写。
     *
     * 调用方负责 [WavReader]/[WavWriter] 的关闭(本方法不关闭,便于外部读 [Stats])。
     */
    fun run(): Stats {
        val channels = reader.channels
        val sampleRate = reader.sampleRate
        val totalFrames = reader.totalFrames

        // ★G-3：分块计划（支持固定块与**变长递增块**统一表达）。
        val plan = buildPlan(totalFrames)
        // 只在**真的发生分块**时才校验几何约束。
        // 短音频(`total <= chunk`)只有 1 块、不存在重叠区,此时 overlap 参数
        // 完全不相干 —— 无条件校验会无故拒绝合法的单块场景(测试曾命中此问题)。
        if (plan.size > 1) requireChunkGeometry(plan)

        val pending = PendingTail(channels, overlapFrames)
        val lengths = ArrayList<Int>(plan.size)

        Log.i(
            TAG,
            "streaming: total=$totalFrames frames ch=$channels sr=$sampleRate " +
                "overlap=$overlapFrames chunks=${plan.size} lens=${plan.take(6).map { it.len }}",
        )

        plan.forEachIndexed { idx, chunk ->
            val start = chunk.start
            val isFirst = idx == 0
            val isLast = idx == plan.lastIndex
            // 本块自己的 hop（变长时每块不同）：head/body/tail 三区均以它为准。
            val hop = chunk.hop
            val want = chunk.len
            val block = reader.readFrames(start, want)
            require(block.isNotEmpty()) { "empty read at frame $start" }

            val res = separator.separate(block, channels, sampleRate)
            require(res.sampleRate == sampleRate) {
                "separator returned sr=${res.sampleRate}, expected $sampleRate"
            }
            val lFrames = res.vocals.size / channels
            require(lFrames == res.accompaniment.size / channels) {
                "stems have different lengths: " +
                    "vocals=${lFrames} accomp=${res.accompaniment.size / channels}"
            }
            lengths += lFrames
            if (lFrames <= 0) {
                Log.w(TAG, "chunk #$idx (start=$start want=$want) produced 0 frames, skipped")
                return@forEachIndexed
            }
            // 不变量:非尾块必须至少覆盖到下一块的起点,否则块间会留空洞。
            if (!isLast) {
                require(lFrames >= hop) {
                    "chunk #$idx 输出 $lFrames 帧 < hop $hop 帧 → 块间会留空洞。" +
                        "请增大 overlapFrames(当前 $overlapFrames)"
                }
            }

            // ---- head 区:与本块 fade-in 做加权合并后顺序写出 ----
            // 覆盖 [start, start+mergeLen);pending 的帧(≤ overlap)必落在其中。
            val headLen = minOf(overlapFrames, lFrames)
            val mergeLen = maxOf(headLen, pending.frames)
            if (mergeLen > 0) {
                val v = FloatArray(mergeLen * channels)
                val a = FloatArray(mergeLen * channels)
                for (f in 0 until mergeLen) {
                    // 先算权重和,再算加权和,最后统一归一化。
                    // 两块交叠时 sumW 恰为 1;单覆盖时 sumW == 唯一那块自身的权重。
                    val inChunk = f < headLen
                    val inPend = f < pending.frames
                    val w = if (inChunk) weightAt(f, lFrames, isFirst, isLast) else 0f
                    val wp = if (inPend) pending.weights[f] else 0f
                    val sumW = w + wp
                    if (sumW <= WEIGHT_EPS) continue
                    val dst = f * channels
                    for (c in 0 until channels) {
                        val src = f * channels + c
                        var sv = 0f
                        var sa = 0f
                        if (inChunk) {
                            sv += res.vocals[src] * w
                            sa += res.accompaniment[src] * w
                        }
                        if (inPend) {
                            sv += pending.vocals[src]
                            sa += pending.accomp[src]
                        }
                        v[dst + c] = sv / sumW
                        a[dst + c] = sa / sumW
                    }
                }
                vocalsWriter.append(v)
                accompWriter.append(a)
                pending.clear()
            } else {
                pending.clear()
            }

            // ---- 其余部分 ----
            if (isLast) {
                // 末块之后没有别的块,剩余帧单覆盖 → 直写原始样本,零拷贝。
                appendRaw(res.vocals, res.accompaniment, mergeLen, lFrames - mergeLen)
            } else {
                // body/已 settle 部分 [mergeLen, hop):只被本块覆盖 → 直写,零拷贝。
                val settled = hop - mergeLen
                if (settled > 0) {
                    appendRaw(res.vocals, res.accompaniment, mergeLen, settled)
                }
                // tail [hop, lFrames):与下一块的头交叠,暂存待合并。
                // 权重是 fadeOut 斜坡(该区恒 ≥ hop ≥ overlap,故 fadeIn 恒为 1)。
                pending.stash(
                    srcOffset = hop,
                    count = lFrames - hop,
                    weights = { f -> weightAt(f, lFrames, isFirst, isLast) },
                    vocals = res.vocals,
                    accomp = res.accompaniment,
                )
            }

            onProgress?.invoke(minOf(start + lFrames, totalFrames), totalFrames)
        }

        // pending 应已被最后一块清空;若因参数组合留有残余,宁可报错也不要静默丢数据。
        check(pending.frames == 0) {
            "结束时仍残留 ${pending.frames} 帧未写出 —— 分块参数组合有误"
        }

        val stats = Stats(
            inputFrames = totalFrames,
            vocalsFrames = vocalsWriter.framesWritten,
            accompFrames = accompWriter.framesWritten,
            chunks = plan.size,
            chunkOutputLengths = lengths,
        )
        Log.i(
            TAG,
            "streaming done: in=${stats.inputFrames} out=${stats.vocalsFrames} " +
                "tailLoss=${stats.tailLossFrames} chunks=${stats.chunks} " +
                "chunkOut=${lengths.take(4)}${if (lengths.size > 4) "…" else ""}",
        )
        return stats
    }

    /** 直写块内 [offsetFrames, offsetFrames+count) 段(单覆盖区,无需加权)。 */
    private fun appendRaw(
        vocals: FloatArray,
        accomp: FloatArray,
        offsetFrames: Int,
        count: Int,
    ) {
        if (count <= 0) return
        vocalsWriter.appendRegion(vocals, offsetFrames, count)
        accompWriter.appendRegion(accomp, offsetFrames, count)
    }

    /**
     * 块内局部下标 [f] 的权重。`w = fadeIn * fadeOut`,见类注释。
     */
    private fun weightAt(f: Int, lFrames: Int, isFirst: Boolean, isLast: Boolean): Float {
        var w = 1f
        if (!isFirst && f < overlapFrames) {
            w *= (f + 0.5f) / overlapFrames
        }
        if (!isLast && f >= lFrames - overlapFrames) {
            w *= (lFrames - 1 - f + 0.5f) / overlapFrames
        }
        return w
    }

    /**
     * 块的起始帧序列。
     *
     * 尾块用「最后一个使 `start + chunk < total` 的步进值」定位,而不是简单地在
     * `start < total` 时继续 —— 后者会产生极小的尾块(可能只有几帧),而
     * `lFrames = N - (N mod 1024)` 对极小输入会直接归零。
     * 由 `hop = chunk - overlap` 可证尾块长度 ∈ (overlap, chunk],故不会退化。
     */
    private fun chunkStarts(totalFrames: Int, chunk: Int, hop: Int): List<Int> {
        // 短音频:单块,无重叠。
        if (totalFrames <= chunk) return listOf(0)
        val starts = ArrayList<Int>()
        var s = 0
        while (s + chunk < totalFrames) {
            starts += s
            s += hop
        }
        starts += s
        return starts
    }

    /**
     * 校验分块几何约束。**仅在真的分块(size > 1)时调用** —— 单块不存在重叠区。
     * 变长块：**逐块**校验（每块自己的 len/hop）。
     */
    private fun requireChunkGeometry(plan: List<ChunkPlan>) {
        // 约束 A:sherpa 每次调用末尾最多丢 1 个 hop,重叠小于 1 hop 会留空洞。
        require(overlapFrames > MAX_DROPPED_FRAMES_PER_CALL) {
            "overlapFrames($overlapFrames) 必须 > ${MAX_DROPPED_FRAMES_PER_CALL}(1 个 hop)," +
                "否则块间会留下未被任何块覆盖的空洞"
        }
        // 约束 B(正确性前提):**非尾块** len >= 2*overlap ⇔ overlap <= hop。
        //   只有如此,"body 区只被本块覆盖"才成立,直写原始样本才正确。
        //   尾块没有下一块,不参与 body 单覆盖推理（且 total 略超一块时尾块可以很短）→ 不校验。
        plan.forEachIndexed { i, c ->
            require(c.len > 0) { "chunk #$i len 必须 > 0，got ${c.len}" }
            val isLast = i == plan.lastIndex
            if (!isLast) {
                require(c.len >= 2 * overlapFrames) {
                    "chunk #$i len(${c.len}) 必须 >= 2 * overlapFrames($overlapFrames) ——" +
                        "否则头/尾区会互相穿插,分块拼接的权重归一化不再成立"
                }
            }
        }
    }

    /**
     * 待合并的块尾(pending tail)。
     *
     * 索引约定:下标 0 对应**下一块的起始帧**(即本块的 `start + hop`)。
     * 这样下一块处理 head 区时,pending 的第 i 项与「块的局部下标 i」天然对齐,
     * 无需再做坐标换算。
     *
     * 容量 ≤ `overlap` 帧:由约束 A/B 可证 `count = lFrames - hop <= overlap`。
     * 故内存与歌曲总长无关(guide N2)。
     */
    private class PendingTail(private val channels: Int, val overlapFrames: Int) {
        var frames = 0
            private set
        val vocals = FloatArray(overlapFrames * channels)
        val accomp = FloatArray(overlapFrames * channels)
        val weights = FloatArray(overlapFrames)

        /** 把块内 `[srcOffset, srcOffset+count)` 段乘以权重后暂存(索引从 0 重新计数)。 */
        fun stash(
            srcOffset: Int,
            count: Int,
            weights: (Int) -> Float,
            vocals: FloatArray,
            accomp: FloatArray,
        ) {
            require(count >= 0 && count <= overlapFrames) {
                "pending 容量不足: count=$count > overlap=$overlapFrames"
            }
            clear()
            for (i in 0 until count) {
                val w = weights(srcOffset + i)
                val src = (srcOffset + i) * channels
                val dst = i * channels
                for (c in 0 until channels) {
                    this.vocals[dst + c] = vocals[src + c] * w
                    this.accomp[dst + c] = accomp[src + c] * w
                }
                this.weights[i] = w
            }
            frames = count
        }

        fun clear() {
            frames = 0
        }
    }

    companion object {
        private const val TAG = "StreamingSep"

        /**
         * 默认块长 30s @ 44.1kHz(1,323,000 帧)。
         *
         * Task 0g 实测(喀秋莎 2:48,4线程,6G RAM 环境)四组块长对比:
         *   5s→14.8× | 10s→23.5× | 15s→20.1× | **30s→24.7×,PSS 1048MB**
         * 30s 块最快(块数少→STFT 重叠计算与 forward 次数少)且边界次数最少
         * (伪影概率最低),故取代 15s 成为默认。
         *
         * 历史注:旧 1GB RAM 环境下 30s 块因 48MB Java 堆必 OOM,当时用 15s;
         * 15s 的块内存账(≈23.8MB 堆 + native)仍适用于低配设备的降级档。
         */
        const val DEFAULT_CHUNK_FRAMES = 1_323_000

        /** 4096 帧 ≈ 93ms —— 是 1 个 hop(1024)的 4 倍,且 < chunk/2,留足余量。 */
        const val DEFAULT_OVERLAP_FRAMES = 4096

        /**
         * sherpa 单次调用末尾最多丢弃的帧数 = 1 个 hop。
         * 由 Task 0d 实测数据推导:`N_out = N_in - (N_in mod 1024)`。
         */
        const val MAX_DROPPED_FRAMES_PER_CALL = 1024

        private const val WEIGHT_EPS = 1e-6f
    }
}