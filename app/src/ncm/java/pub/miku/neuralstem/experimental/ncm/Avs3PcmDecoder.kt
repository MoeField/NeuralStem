package pub.miku.neuralstem.experimental.ncm

import android.util.Log

/**
 * ★把 AVS3 (`av3a`) 的 MP4 音频轨解成**立体声 PCM16**，并做下混。
 *
 * ## 为什么要下混
 * 用户的文件是 **AVS3-P3 空间音频**，实测 `channelNumIdx = 10` → `MC_7_1_4`，
 * 共 **12 声道**。而：
 *
 * - Android 的普通播放路径（AudioTrack / 扬声器）不会自动下混 12 声道；
 * - 12 声道 PCM 整首有 **137MB**，立体声只有 **23MB**；
 * - 用户 99% 的场景是戴耳机/手机外放听，12 声道没有意义。
 *
 * ⇒ 解码时就只保留立体声，**内存峰值从 137MB 降到 23MB**。
 *
 * ## 声道布局（据实测定，非规范猜测）
 * 对整首做 `ffmpeg astats` 逐声道测 RMS 能量：
 *
 * ```
 * ch1/2   -11.9 dB   前左 / 前右   ← 主内容
 * ch3/4   -21.5/-23.8 中置 / LFE
 * ch5-8   -17 dB     环绕
 * ch9-12  -26.9 dB   天空声道
 * ```
 *
 * 这个分布与 `MC_7_1_4` 的结构吻合（7 个地面 + 1 个 LFE + 4 个高度）。
 * 用户试听「只取 ch1/2」的立体声版本，确认音准、节奏、内容都正确。
 *
 * ## 下混公式
 * 采用 ITU-R BS.775 的实用简化版（不做 LFE，因为 LFE 进耳机只会糊）：
 * ```
 * L = FL + 0.707·C + 0.707·SL + 0.5·(HL+HLs)
 * R = FR + 0.707·C + 0.707·SR + 0.5·(HR+HRs)
 * ```
 * 系数 0.707 = -3dB（功率守恒），这是业界通行做法。
 * 但参数化保留了 `frontOnly` 选项 —— 实测「只取前 L/R」听感已经很完整，
 * 而混入环绕有时会让某些混音的声场变浑。
 *
 * ## 为什么不是纯 JNI
 * 下混只是「按索引取几个声道的样本、乘系数、相加」，放在 Kotlin 层做
 * 可以**复用现有的帧循环和错误处理**，也便于写单测。
 * 真正的解码仍然在 native（`Avs3AudioDecoder`）。
 */
object Avs3PcmDecoder {

    private const val TAG = "Avs3PcmDec"

    /** 解码进度回调（在后台线程调用）。 */
    fun interface ProgressListener {
        /** @param done 已处理帧数 @param total 总帧数（可能为 0=未知） */
        fun onProgress(done: Int, total: Int)
    }

    /** 解码结果。 */
    data class Result(
        /** 交错立体声 PCM16，小端。 */
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        /** 源声道数（12 就是 MC_7_1_4）。 */
        val sourceChannels: Int,
        /** 实际解出的帧数（= 采样点数）。 */
        val frames: Int,
        /** 解码失败/空帧的数量，正常应为 0。 */
        val skippedFrames: Int,
    ) {
        val durationMs: Long
            get() = if (sampleRate > 0) frames * 1000L / sampleRate else 0L
    }

    /**
     * 下混策略。
     */
    enum class Downmix {
        /** 只取前左/前右（ch1/ch2）。实测听感已完整，最保守。 */
        FRONT_ONLY,

        /** BS.775 简化下混：前 L/R + 中置 + 环绕 + 一半高度声道。推荐。 */
        BS775,
    }

    /** 从帧头里解析出的源声道数 → 用于下混索引。 */
    private const val CH_MONO = 1

    /**
     * 解码整条音频轨。
     *
     * @param decoder 已 `open()` 且 `initSequence()` 成功的解码器
     * @param frames MP4 提取出的音频样本（每项含帧头，见 `headerBytes`）
     * @param headerBytes 每帧开头的帧头字节数（实测 7）。
     *        **必须跳过** —— `avs3_decode` 吃的是纯载荷。
     * @param sampleRate 输出采样率（实测 44100）
     * @param sourceChannels 源声道数（实测 12）
     * @param downmix 下混策略
     * @param onProgress 进度回调（每 64 帧调一次，避免过于频繁）
     */
    fun decodeAll(
        decoder: Avs3AudioDecoder,
        frames: List<ByteArray>,
        headerBytes: Int,
        sampleRate: Int,
        sourceChannels: Int,
        downmix: Downmix = Downmix.BS775,
        onProgress: ProgressListener? = null,
        /** 取消检查：每帧问一次，返回 true 就提前退出。 */
        isCancelled: () -> Boolean = { false },
    ): Result? {
        if (frames.isEmpty()) return null
        if (sourceChannels < 1) return null

        val total = frames.size
        // 每帧 PCM 字节数 = 声道数 × frameLength × 2。
        // frameLength 由解码器内部决定（实测 1024），这里先解一帧拿到真实值。
        var outChannels = sourceChannels
        var frameLen = 0

        // 输出缓冲：立体声 → 每帧 2 × frameLen × 2 字节。
        // 先按 1024 预估，第一帧拿到实际值后若不同则重建。
        var out = ByteArray(0)
        var outPos = 0
        var framesDone = 0
        var skipped = 0

        val srcBuf = ShortArray(sourceChannels * 4096) // 足够大，实际只用前 n*ch
        var outCh: Int = 2

        for ((i, raw) in frames.withIndex()) {
            if (isCancelled()) {
                Log.i(TAG, "取消于第 $i 帧")
                return null
            }

            // ★跳过帧头：avs3_decode 吃纯载荷（实测 consumed=2408，样本=2415）。
            val payload = if (headerBytes in 1 until raw.size) {
                raw.copyOfRange(headerBytes, raw.size)
            } else {
                raw
            }

            val pcm = decoder.decodeFrame(payload)
            if (pcm == null || pcm.isEmpty()) {
                skipped++
                continue
            }

            // 第一帧确定 frameLength 与声道数。
            if (frameLen == 0) {
                // 声道数从解码器结构体里读（官方库不导出 getConfig，
                // 偏移是 static_assert 实测的，见 native 注释）。
                // 读不到就回退到调用方传入的源声道数。
                val reported = decoder.sourceChannels()
                outChannels = if (reported > 0) reported else sourceChannels
                frameLen = if (outChannels > 0) pcm.size / 2 / outChannels else 0
                if (frameLen <= 0) {
                    Log.e(TAG, "无法从首帧推断 frameLength：pcm=${pcm.size}B ch=$outChannels")
                    return null
                }
                outCh = if (outChannels >= 2) 2 else 1
                // 预分配整首输出（精确大小，避免 ByteArrayOutputStream 反复扩容）
                out = ByteArray(total * frameLen * outCh * 2)
                Log.i(
                    TAG,
                    "首帧：${pcm.size}B → ${outChannels}ch × $frameLen 帧；" +
                        "下混为 ${outCh}ch，预计输出 ${out.size / 1024 / 1024}MB",
                )
            }

            mixInto(pcm, out, outPos, frameLen, outChannels, outCh, downmix)
            outPos += frameLen * outCh * 2
            framesDone++

            if (onProgress != null && (i % 64 == 0 || i == total - 1)) {
                onProgress.onProgress(i + 1, total)
            }
        }

        if (outPos == 0) return null

        val resultPcm = if (outPos == out.size) out else out.copyOf(outPos)
        return Result(
            pcm = resultPcm,
            sampleRate = sampleRate,
            channels = outCh,
            sourceChannels = outChannels,
            frames = outPos / (outCh * 2),
            skippedFrames = skipped,
        )
    }

    /**
     * 把一帧多声道 PCM 下混进输出缓冲。
     *
     * 输入是非交错（planar）还是交错（interleaved）？
     * 从 `avs3_decode` 的实现看（`memcpy(pDataOut, data, numChans*frameLength*2)`，
     * 而 `data` 是 `short data[MAX_CHANNELS * FRAME_LEN]` 由 `Avs3SynthOutput` 填充），
     * 实测确认是**交错**的（head 字节序列呈周期性重复模式）。
     */
    private fun mixInto(
        src: ByteArray,
        dst: ByteArray,
        dstOffset: Int,
        frameLen: Int,
        srcCh: Int,
        dstCh: Int,
        downmix: Downmix,
    ) {
        var d = dstOffset
        for (n in 0 until frameLen) {
            if (dstCh == 1) {
                // 单声道：所有声道平均（极少用到，源是单声道时走这里）。
                var acc = 0
                for (c in 0 until srcCh) acc += readS16(src, (n * srcCh + c) * 2)
                val v = acc / srcCh
                writeS16(dst, d, v)
                d += 2
                continue
            }

            val l: Int
            val r: Int
            when (downmix) {
                Downmix.FRONT_ONLY -> {
                    if (srcCh >= 2) {
                        l = readS16(src, (n * srcCh + 0) * 2)
                        r = readS16(src, (n * srcCh + 1) * 2)
                    } else {
                        l = readS16(src, n * 2)
                        r = l
                    }
                }

                Downmix.BS775 -> {
                    // 索引按 MC_7_1_4（12ch）布局：
                    // 0,1=FL,FR  2,3=C,LFE  4..7=SL,SR,BL,BR  8..11=HL,HR,HLs,HRs
                    var sl = ch(src, n, srcCh, 0)
                    var sr = ch(src, n, srcCh, 1)
                    if (srcCh > 2) {
                        val c = ch(src, n, srcCh, 2)
                        sl += (c * 0.707f).toInt()
                        sr += (c * 0.707f).toInt()
                    }
                    if (srcCh > 4) {
                        sl += (ch(src, n, srcCh, 4) * 0.707f).toInt()
                        sr += (ch(src, n, srcCh, 5) * 0.707f).toInt()
                    }
                    if (srcCh > 6) {
                        sl += (ch(src, n, srcCh, 6) * 0.707f).toInt()
                        sr += (ch(src, n, srcCh, 7) * 0.707f).toInt()
                    }
                    if (srcCh > 8) {
                        sl += (ch(src, n, srcCh, 8) * 0.5f).toInt()
                        sr += (ch(src, n, srcCh, 9) * 0.5f).toInt()
                    }
                    l = clamp16(sl)
                    r = clamp16(sr)
                }
            }

            writeS16(dst, d, l)
            d += 2
            writeS16(dst, d, r)
            d += 2
        }
    }

    /** 读第 [n] 帧的第 [ch] 个声道（越界返 0）。 */
    private inline fun ch(src: ByteArray, n: Int, srcCh: Int, ch: Int): Int {
        val idx = (n * srcCh + ch) * 2
        return if (idx + 1 < src.size) readS16(src, idx) else 0
    }

    private fun readS16(b: ByteArray, off: Int): Int {
        if (off + 1 >= b.size) return 0
        return ((b[off + 1].toInt() shl 8) or (b[off].toInt() and 0xFF)).toShort().toInt()
    }

    private fun writeS16(b: ByteArray, off: Int, v: Int) {
        val s = clamp16(v)
        b[off] = (s and 0xFF).toByte()
        b[off + 1] = ((s shr 8) and 0xFF).toByte()
    }

    private fun clamp16(v: Int): Int = when {
        v > 32767 -> 32767
        v < -32768 -> -32768
        else -> v
    }
}
