package pub.miku.neuralstem.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import pub.miku.neuralstem.devtools.EdgeProbe
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * ★多轨混音器：**一个 AudioTrack，所有 stem 在同一采样计数上求和**（2026-11-01）。
 *
 * ## 为什么要重写（真机实测的架构结论）
 * 旧方案是「每条 stem 一个 ExoPlayer」，于是有 N 个独立 AudioTrack。
 * 实测后果（BF00A）：
 * - 起播后轨间差 **800–890ms**（每条 AudioTrack 首次启动各需 ~800ms）
 * - 校正 105 次无效（seek 会重新触发 buffer，越修越乱）
 * - 4 条并发 AudioTrack 在本机轮流 underrun → **某轨中途暂停**
 * - 更根本的：`currentPosition` 是**媒体时钟**，不是出声位置，
 *   各轨缓冲深度不同（实测 3776 vs 4104 帧）→ 位置看起来对齐，听感仍错位
 *
 * ## 专业软件怎么做（DAW / DJ / 游戏引擎）
 * 都不是“同步多个时钟”，而是**只用一个时钟**：一个音频回调里，
 * 按同一个采样计数把 N 条轨混音写进同一个 buffer。本类就是这件事。
 *
 * ## 线程模型
 * - 渲染线程：`while (running) { 混合一块 → AudioTrack.write() }`
 * - `write()` 阻塞式：天然形成“按消费速度推进”的节拍器，不需要自己 sleep
 * - 所有对外的控制方法（play/pause/seek）都**只改 volatile 状态**，不阻塞调用方
 *
 * ## 音量
 * 每条轨 `volume × (muted ? 0 : 1) × (solo ? 1 : 0)` 在**混音时**相乘，
 * 不做 AudioTrack.setVolume（那是整个 mix 的音量）。
 */
class StemMixer(
    private val sampleRate: Int,
    private val channels: Int,
    /** 每条轨的混音系数提供者（音量/静音/独奏已折算好，0..1）。 */
    private val gainProvider: (id: String) -> Float,
) : Closeable {

    private class Voice(val id: String, val source: StemSource)

    private val voices = ArrayList<Voice>()

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    /** 渲染线程代际：stop 后旧线程即使还卡在 write 也不能继续写/复活。 */
    @Volatile private var renderGeneration: Long = 0L

    /**
     * **源读头**（绝对帧）。渲染线程推进；seek 时由外部改写。
     *
     * ★2026-11-06：变速后它**不再等于媒体位置**（2× 时播 1 秒只前进 0.5 秒媒体）。
     * 对外位置一律看 [playFrame]。
     */
    @Volatile private var framePos: Long = 0L

    /**
     * 对外报告的**媒体帧位置**：旁路时 = [framePos]；变速时 = Sonic 的输出映射。
     * 渲染线程每块写一次，主线程只读。
     */
    @Volatile private var playFrame: Long = 0L

    @Volatile private var playing = false

    /** 是否已到曲尾（供 Service/VM 触发自动下一首；seek/重播会清掉）。 */
    @Volatile var ended: Boolean = false
        private set

    /** 请求 seek 到的目标帧；渲染线程看到后跳到该处。 */
    @Volatile private var seekRequest: Long = -1L

    /** 目标参数（UI/命令线程写；渲染线程读并应用）。 */
    @Volatile private var targetSpeed: Float = 1f
    @Volatile private var targetPitchRate: Float = 1f

    /** 渲染线程已生效的参数（与 target 比较，避免每块重复 flush）。 */
    private var appliedSpeed: Float = 1f
    private var appliedPitchRate: Float = 1f

    /** 输出端 Sonic（1.0× 时 `isActive == false`，走原路径零开销）。 */
    private val stage = SonicStage(sampleRate, channels, CHUNK_FRAMES)

    /** 本曲总帧数（取所有 voice 的最小值，短的先结束）。 */
    val totalFrames: Long
        get() = voices.minOfOrNull { it.source.totalFrames } ?: 0L

    /** 当前播放位置（ms，**媒体时间**，不是写出时间）。 */
    val positionMs: Long
        get() = if (sampleRate <= 0) 0L else playFrame * 1000L / sampleRate

    val durationMs: Long
        get() = if (sampleRate <= 0) 0L else totalFrames * 1000L / sampleRate

    val isPlaying: Boolean get() = playing

    /**
     * 混音输出的 AudioTrack 会话 id（供 EQ 挂载）。
     *
     * ## 为何多轨 EQ 改成挂在**这里**
     * 旧实现每条 stem 各一个 ExoPlayer/AudioTrack，EQ 也各挂一份。
     * 现在只有一个 AudioTrack —— 所以 **EQ 只在混音输出上挂一次**，
     * 语义反而更正确（EQ 作用于最终混音，而不是各轨分别均衡后再相加）。
     *
     * @return 0 = 尚未创建
     */
    val audioSessionId: Int get() = track?.audioSessionId ?: 0

    /**
     * 载入一组 stem。
     *
     * ## 前置条件
     * 所有 source 的 `channels` / `sampleRate` 必须与构造参数一致
     * （混音器不做重采样；不一致会让某轨“变速播放”或直接崩）。
     */
    fun load(items: List<Pair<String, StemSource>>) {
        require(items.isNotEmpty()) { "no stems" }
        items.forEach {
            require(it.second.channels == channels) {
                "${it.first}: channels=${it.second.channels} 期望 $channels"
            }
            require(it.second.sampleRate == sampleRate) {
                "${it.first}: sampleRate=${it.second.sampleRate} 期望 $sampleRate"
            }
        }
        stopThread()
        voices.clear()
        items.forEach { voices += Voice(it.first, it.second) }
        framePos = 0L
        playFrame = 0L
        seekRequest = -1L
        ended = false
        lastBeatFrame = 0L
        // 线程已停 → 可以安全重置 Sonic 时钟；参数保持 target（Service 在 load 后会下发）。
        stage.flushAt(0L)
        appliedSpeed = 1f
        appliedPitchRate = 1f
        ensureTrack()
        Log.i(TAG, "load: ${voices.size} voice(s) ${voices.map { it.id }} @${sampleRate}Hz/${channels}ch")
    }

    /**
     * 设置变速/变调（任意线程可调；渲染线程下一块生效）。
     *
     * @param speed 速度倍率（1.0 = 原速）
     * @param pitchRate 音高倍率（= 2^(semitones/12)，1.0 = 原调）
     */
    fun setSpeedPitch(speed: Float, pitchRate: Float) {
        val s = if (speed.isFinite() && speed > 0f) speed else 1f
        val p = if (pitchRate.isFinite() && pitchRate > 0f) pitchRate else 1f
        if (s == targetSpeed && p == targetPitchRate) return
        targetSpeed = s
        targetPitchRate = p
        Log.i(TAG, "setSpeedPitch: speed=$s pitchRate=$p")
        EdgeProbe.log("Mixer", "params speed=$s pitch=$p")
    }

    /** 启动渲染线程（幂等）。 */
    fun play() {
        if (voices.isEmpty()) return
        playing = true
        ensureTrack()
        val t = track ?: return
        // ★2026-11-06：曲尾后线程已退出，但 `running` 可能还是 true（旧实现的遗留）→
        //   只按 running 判断会“按播放没反应”。改为看线程是否还活着。
        val aliveThread = thread
        if (aliveThread != null && aliveThread.isAlive) {
            // 同一个渲染线程已在跑：让它继续；旧线程正在退出时也绝不启动第二个 writer。
            if (!running) Log.w(TAG, "play: 旧渲染线程仍在退出，暂不启动新线程")
            return
        }
        // 在曲尾按播放：从 0 重来（否则读头在 totalFrames，启动即又结束）。
        if (totalFrames > 0 && framePos >= totalFrames) {
            framePos = 0L
            playFrame = 0L
            ended = false
            lastBeatFrame = 0L
            stage.flushAt(0L)
            EdgeProbe.log("Mixer", "play at end → restart from 0")
        }
        // ★★ 必须真的让 AudioTrack 进入 PLAYSTATE_PLAYING，否则 write() 会
        //   永远阻塞（以为消费者没在消费）—— 真机实测：渲染线程启动后无任何日志，
        //   位置冻结在 46ms。
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { t.play() }.onFailure { Log.w(TAG, "AudioTrack.play() 失败", it) }
        }
        running = true
        renderGeneration++
        val gen = renderGeneration
        thread = Thread({ renderLoop(gen) }, "StemMixer").apply {
            priority = Thread.MAX_PRIORITY      // 音频线程要高优先级，否则容易 underrun
            start()
        }
        Log.i(TAG, "play: 渲染线程启动 @${framePos}frame (trackState=${t.playState})")
        EdgeProbe.log("Mixer", "play @${framePos}f (thread=${thread?.isAlive})")
    }

    fun pause() {
        playing = false
        Log.i(TAG, "pause @${framePos}frame")
        EdgeProbe.log("Mixer", "pause @${framePos}f")
    }

    fun seekTo(positionMs: Long) {
        val f = (positionMs.coerceAtLeast(0L) * sampleRate / 1000L)
        seekRequest = f
        // 立刻反映到对外位置（UI 不用等渲染线程）
        framePos = f
        playFrame = f
        ended = false
        Log.i(TAG, "seek → ${positionMs}ms (${f}frame)")
    }

    override fun close() {
        stopThread()
        voices.forEach { runCatching { it.source.close() } }
        voices.clear()
        runCatching { track?.release() }
        track = null
        Log.i(TAG, "closed")
    }

    // ---------------- 内部 ----------------

    private fun stopThread() {
        running = false
        playing = false
        renderGeneration++
        val t = thread
        thread = null
        t?.interrupt()
        t?.let { runCatching { it.join(800) } }
        if (t?.isAlive == true) {
            // join 超时：旧线程可能还卡在 AudioTrack.write。保留引用，
            // play() 看到 isAlive 会拒绝启动第二个 writer，等它退出后再启动。
            thread = t
            Log.w(TAG, "stopThread: 旧渲染线程未在 800ms 内退出，延迟复用")
        }
        runCatching { track?.pause(); track?.flush() }
    }

    private fun ensureTrack() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(CHUNK_FRAMES * channels * 4 * 2)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(
                        if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
                        else AudioFormat.CHANNEL_OUT_STEREO,
                    )
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        Log.i(TAG, "AudioTrack created: buf=$minBuf bytes (${minBuf / (channels * 4)} frames)")
    }

    /**
     * 渲染循环：混合一块 → 写入（write 阻塞形成节拍）。
     *
     * ## ★两层 try/catch 是必需的（2026-11-01 真机事故）
     * 曾出现：拖动进度条后**彻底没声**，`POS_MIX` 冻结在 seek 位置、
     * 但 `playing` 仍是 true（位置不动、也无心跳）。
     * 根因：渲染线程在处理 seek（`AudioTrack.pause/flush/play`）时抛了异常，
     * 线程直接死亡，而 `running/playing` 还是 true → 上层以为在播。
     * 这类“线程静默死亡”极难从外部看出来，所以必须自己接住并设标志。
     */
    private fun renderLoop(gen: Long) {
        val mix = FloatArray(CHUNK_FRAMES * channels)
        val tmp = FloatArray(CHUNK_FRAMES * channels)
        var loopWarn = 0

        while (running && gen == renderGeneration) {
            try {
                // ★每轮重新取 track：write 失败重建后，旧的局部引用会指向已 release 的对象。
                val t = track ?: break

                // ★seek 要放在“暂停检查”**之前**：
                //   否则暂停状态下的 seek 永远不生效（下一轮又 continue 了）。
                val req = seekRequest
                if (req >= 0L) {
                    seekRequest = -1L
                    framePos = req
                    playFrame = req
                    lastBeatFrame = req
                    stage.flushAt(req)          // 丢弃 Sonic 内部残留，时钟锚到新位置
                    // 重定位 AudioTrack（flush 必须先 pause）
                    runCatching { t.pause() }
                    runCatching { t.flush() }
                    runCatching { t.play() }
                    reloadSources(framePos)
                    Log.i(TAG, "renderLoop: seek 生效 @${framePos}frame (playState=${t.playState})")
                }

                // ★参数变更（UI → Service → 这里）：Sonic 只在渲染线程上调用。
                val ts = targetSpeed
                val tp = targetPitchRate
                if (ts != appliedSpeed || tp != appliedPitchRate) {
                    stage.setParams(ts, tp)
                    val newSrc = stage.applyParams(framePos)
                    framePos = newSrc
                    playFrame = newSrc
                    appliedSpeed = ts
                    appliedPitchRate = tp
                    Log.i(
                        TAG,
                        "renderLoop: 应用参数 speed=$ts pitch=$tp " +
                            "active=${stage.isActive} src=$newSrc",
                    )
                    EdgeProbe.log("Mixer", "apply speed=$ts pitch=$tp active=${stage.isActive}")
                }

                if (!playing) {
                    Thread.sleep(10)
                    continue
                }

                // ---- 混音 ----
                java.util.Arrays.fill(mix, 0f)
                var framesRead = 0
                for (v in voices) {
                    val g = gainProvider(v.id)
                    if (g <= 0f) continue        // 静音/未独奏 → 连读都不读，省 IO
                    val n = runCatching { v.source.read(framePos, tmp, CHUNK_FRAMES) }.getOrDefault(0)
                    if (n <= 0) {
                        if (loopWarn < 3) Log.w(TAG, "read[${v.id}] @${framePos} → $n（无数据）")
                        continue
                    }
                    if (g >= 0.999f) {
                        for (i in 0 until n * channels) mix[i] += tmp[i]
                    } else {
                        for (i in 0 until n * channels) mix[i] += tmp[i] * g
                    }
                    if (n > framesRead) framesRead = n
                }

                if (framesRead <= 0) {
                    if (loopWarn++ < 3) {
                        Log.w(
                            TAG,
                            "renderLoop 无数据: framePos=$framePos total=$totalFrames " +
                                "finished=${voices.map { "${it.id}=${it.source.isFinished}" }} " +
                                "gains=${voices.map { "${it.id}=${gainProvider(it.id)}" }}",
                        )
                    }
                    // ★2026-11-06：曲终必须同时满足「所有源都不会再有数据」。
                    //   边播时 `totalFrames` 是**可增长**的，追上写头（framePos == totalFrames）
                    //   不等于结束；旧判断会在这里 break，渲染线程永久退出。
                    //   旧实现靠阻塞读掩盖了这点（读不到数据时会等），现在读是非阻塞的，
                    //   必须显式区分“暂时没数据”和“真的没了”。
                    val allFinished = voices.all { it.source.isFinished }
                    if (allFinished && totalFrames > 0 && framePos >= totalFrames) {
                        Log.i(TAG, "renderLoop: 到达曲尾 @${framePos}/${totalFrames}")
                        // Sonic 有延迟线：把最后 ~31ms 排空，否则曲尾会被截掉。
                        if (stage.isActive) {
                            var err = 0
                            stage.finish { buf, len ->
                                // ★AudioTrack.write 会**自己推进** buffer.position；
                                //   传 duplicate，让 SonicStage 统一推进原 buffer（否则 double-advance）。
                                val w = runCatching {
                                    t.write(buf.duplicate(), len, AudioTrack.WRITE_BLOCKING)
                                }.getOrDefault(-1)
                                if (w < 0) {
                                    err = -1
                                    0
                                } else {
                                    w
                                }
                            }
                            playFrame = stage.positionFrames()
                            EdgeProbe.log(
                                "Mixer",
                                "end sonic play=${playFrame}f " +
                                    "(${playFrame * 1000 / sampleRate}ms) err=$err " +
                                    "active=${stage.isActive}",
                            )
                        }
                        // UI 上到曲尾就应满格（Sonic 映射可能差几十 ms）。
                        playFrame = totalFrames
                        playing = false
                        ended = true
                        // ★让 play() 能以“线程已死”为由重启（旧实现 running 永远 true）。
                        running = false
                        break
                    }
                    // 边播中：不写静音、也不推进 framePos —— 等分离追上，由 Gate 决定暂停/恢复。
                    Thread.sleep(10)
                    continue
                }
                loopWarn = 0

                // 软限幅：多轨相加可能超过 ±1，直接写会削波失真
                clampInPlace(mix, framesRead * channels)

                if (!stage.isActive) {
                    // ---- 旁路：1.0×，与旧路径完全一致 ----
                    val written = t.write(mix, 0, framesRead * channels, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        Log.w(TAG, "AudioTrack.write 失败: $written → 重建 AudioTrack")
                        runCatching {
                            track?.release()
                            track = null
                        }
                        ensureTrack()
                        Thread.sleep(20)
                        continue
                    }
                    framePos += (written / channels).toLong()
                    playFrame = framePos
                } else {
                    // ---- Sonic：变速/变调 ----
                    var writeErr = 0
                    stage.process(mix, framesRead) { buf, len ->
                        // ★同上：AudioTrack 会推进传入 buffer 的 position，必须给它 duplicate。
                        val w = runCatching {
                            t.write(buf.duplicate(), len, AudioTrack.WRITE_BLOCKING)
                        }.getOrDefault(-1)
                        if (w < 0) {
                            writeErr = -1
                            0
                        } else {
                            w
                        }
                    }
                    // 源读头按**读入**帧推进；对外位置由 Sonic 的输出映射得出。
                    framePos += framesRead
                    playFrame = stage.positionFrames()
                    if (writeErr < 0) {
                        Log.w(TAG, "Sonic 输出写入失败 → 重建 AudioTrack")
                        runCatching {
                            track?.release()
                            track = null
                        }
                        ensureTrack()
                        Thread.sleep(20)
                        continue
                    }
                }

                // ★心跳：确认渲染线程真的在推进（排查“位置卡住”时靠它）
                //   seek 回退/改参回退后 playFrame 会小于 lastBeatFrame → 先把基准拉回来，
                //   否则要等超过旧位置才会再打心跳（真机排查时被这个坑过）。
                if (playFrame < lastBeatFrame) lastBeatFrame = playFrame
                if (playFrame - lastBeatFrame >= sampleRate * 5) {
                    lastBeatFrame = playFrame
                    Log.i(
                        TAG,
                        "heartbeat: src=$framePos play=$playFrame " +
                            "(${playFrame * 1000 / sampleRate}ms) speed=$appliedSpeed " +
                            "active=${stage.isActive}",
                    )
                    // 仅 debug 落文件（vivo 吞 logcat）；每 5s 一行，用来证明位置真的在推进。
                    EdgeProbe.log(
                        "Mixer",
                        "hb ${playFrame * 1000 / sampleRate}ms speed=$appliedSpeed",
                    )
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.i(TAG, "renderLoop: 被中断，退出")
                break
            } catch (ex: Throwable) {
                // ★线程不能死：死掉后 running/playing 仍是 true，上层会以为在播，
                //   表现为“位置冻结、完全没声”，极难排查（真机事故）。
                Log.e(TAG, "renderLoop 异常 @${framePos}frame，继续循环", ex)
                runCatching {
                    track?.pause(); track?.flush(); track?.play()
                }
                Thread.sleep(50)
            }
        }
        Log.i(TAG, "renderLoop 退出 @src=$framePos play=$playFrame")
    }

    /**
     * seek 后重置数据源。
     *
     * 文件源天生支持随机读，但 [RingBufferStemSource] 的 cursor 不能回退太前，
     * 统一调一次也没坏处（幂等）。失败的单个源不影响其他源。
     */
    private fun reloadSources(toFrame: Long) {
        voices.forEach { v ->
            runCatching { v.source.read(toFrame, FloatArray(channels), 0) }
        }
    }

    @Volatile private var lastBeatFrame = 0L

    /**
     * 软限幅：超过 ±1 的值压到 ±1。
     *
     * ## 为何必须做
     * 多轨求和后峰值可达 N×，直接写入 AudioTrack 会**硬削波**（刺耳失真）。
     * 更讲究的做法是整体降增益（如 `1/sqrt(N)`），但那会让“只开一轨”时音量偏小。
     * 这里是“只在超限时才压”的折中，正常听感最好。
     */
    private fun clampInPlace(buf: FloatArray, n: Int) {
        var clipped = 0
        for (i in 0 until n) {
            val v = buf[i]
            if (v > 1f) {
                buf[i] = 1f; clipped++
            } else if (v < -1f) {
                buf[i] = -1f; clipped++
            }
        }
        if (clipped > 0 && !warnedClip) {
            warnedClip = true
            Log.w(TAG, "mix 削波 $clipped 样本（多轨叠加超过满幅）→ 已软限幅")
        }
    }

    @Volatile private var warnedClip = false

    companion object {
        private const val TAG = "StemMixer"

        /** 每次混合的帧数。1024 @44.1k ≈ 23ms，兼顾延迟与调用开销。 */
        private const val CHUNK_FRAMES = 1024
    }
}
