package pub.miku.neuralstem.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import java.io.Closeable

/**
 * 多轨同步播放控制器(Task 4.4,guide F4.1–F4.4)。
 *
 * ## 为什么选「多 ExoPlayer 实例」(guide §3.6 方案 A)
 * Task 0 的分离输出是**整轨 PCM WAV**(vocals.wav / accompaniment.wav),
 * 各自已含完整时间轴;方案 A 让每个实例独立解码+渲染,架构最简单。
 * 方案 B(单实例 + 自研 AudioProcessor 混音)需要自己实现混音 DSP,
 * 在已有 Sonic 变速变调的情况下还要保证处理顺序正确 —— 复杂度高得多。
 *
 * ## 关键问题:seek 对齐(guide §3.6「不能出现可感知的相位偏差」)
 * 对策三条(按重要性排序):
 * 1. **同一帧内下发所有命令**:所有实例的 seekTo/play/pause 在同一循环里
 *    连续调用,不跨线程/不跨帧 —— 偏差被压到消息队列粒度
 * 2. **playWhenReady 同帧翻转**:避免「A 已开声、B 还差一帧」的错位
 * 3. **共享 PlaybackParameters**:变速变调对所有轨用同一组参数(F4.4),
 *    避免各轨 Sonic 处理深度不同导致时间轴漂移
 */
@UnstableApi
class MultiTrackController(context: Context) : Closeable {

    /**
     * 一条轨的播放器 + 元信息。
     *
     * @param id 稳定标识("vocals" / "accompaniment")
     * @param label 显示名
     */
    class Track(
        val id: String,
        val label: String,
        val player: ExoPlayer,
        val uri: Uri,
        /**
         * 是否由本控制器“拥有”（需要 release）。
         * M3-P2(v2):主轨可能是外部的 singlePlayer（不由本控制器拥有）
         * —— 退出多轨时**不能** release 它。
         */
        internal val ownsPlayer: Boolean = true,
    ) {
        /** F4.2 各轨独立音量 0..1(只改字段;实际下发由控制器调 [applyEffectiveVolume]) */
        var volume: Float = 1f
            set(value) {
                field = value.coerceIn(0f, 1f)
            }

        /** F4.3 静音(只改字段;实际下发由控制器) */
        var muted: Boolean = false

        /** 由控制器统一计算生效音量(考虑静音与独奏) */
        internal var effectiveVolume: Float = 1f

        /**
         * 音量下发出口 —— **唯一**写 `player.volume` 的地方。
         *
         * 修正(真机反馈「伴奏没声音」):原先 volume/muted 的 setter 直接改
         * `player.volume`,与 [applySoloMute] 各写一份 —— 后者知道 solo 而前者不知,
         * 两者交替调用会互相覆盖。现统一为「setter 只管字段,实际下发由控制器算」。
         */
        internal fun applyEffectiveVolume(vol: Float) {
            effectiveVolume = vol
            player.volume = vol
        }
    }

    private val appContext = context.applicationContext
    private val tracks = LinkedHashMap<String, Track>()

    /** ★对齐起播用：所有轨共用的起播位置（ms）。 */
    private var startAnchorMs: Long = 0L

    /** ★对齐起播超时傅底（防止某轨永远不 READY 时完全不起播）。 */
    private var pendingStartTimeout: Runnable? = null

    /** ★漂移校正周期任务（播放中持续把落后的跟随轨拉回）。 */
    private var driftRunnable: Runnable? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** F4.3 独奏:非 null 时只有该轨发声 */
    var soloTrackId: String? = null
        set(value) {
            field = value
            applySoloMute()
        }

    /** 当前共享的变速变调参数(F4.4) */
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT

    val trackList: List<Track> get() = tracks.values.toList()
    val isEmpty: Boolean get() = tracks.isEmpty()

    /**
     * 载入多轨。会释放已有的所有轨。
     *
     * @param items (id, label, uri) 三元组列表 —— 顺序即混音顺序
     * @param title 曲目显示名 —— 会写进**主轨**的 MediaMetadata（通知标题）
     * @param externalPrimary M3-P2(v2)：**主轨复用外部 player**（即 `PlaybackService.singlePlayer`）。
     *        非 null 时，第一条轨不再新建 player，而是改用它（session 看的就是它
     *        → 通知/前台服务天然工作）。只设 mediaItem，**不** prepare/release 它。
     */
    fun load(
        items: List<Triple<String, String, Uri>>,
        title: String? = null,
        externalPrimary: ExoPlayer? = null,
    ) {
        releaseAll()
        items.forEachIndexed { index, (id, label, uri) ->
            val isPrimary = index == 0
            // v2：主轨复用外部 player（session 的那个），其余轨自建
            val ownsPlayer = !(isPrimary && externalPrimary != null)
            val player = if (isPrimary && externalPrimary != null) {
                externalPrimary
            } else {
                buildPlayer(primary = isPrimary)
            }
            // 主轨带曲目名（通知标题），其余轨带本轨名
            val display = if (isPrimary && !title.isNullOrBlank()) title else label
            val item = MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(display)
                        .setArtist("NeuralStem")
                        .build(),
                )
                .build()
            player.setMediaItem(item)
            // 新建的轨必须挂上已注册的监听
            listeners.forEach { player.addListener(it) }
            if (isPrimary && ownsPlayer) attachFocusHandling(player)
            player.prepare()
            player.playbackParameters = playbackParameters
            tracks[id] = Track(id, label, player, uri, ownsPlayer)
        }
        applySoloMute()
        Log.i(TAG, "loaded ${tracks.size} tracks (externalPrimary=${externalPrimary != null}): ${tracks.keys.joinToString()}")
    }

    /**
     * 建一个 stem 播放器。
     *
     * ⚠️ **关键：只有第一条轨参与音频焦点管理**（`handleAudioFocus`）。
     *
     * 真机实测（「一会人声没、一会伴奏没」的真因）：
     * 若每个实例都 `handleAudioFocus=true`，载入轨时会有 N 个实例
     * 同时 `requestAudioFocus` —— Android 只允许一个焦点持有者，
     * 后申请的会让先申请者收到 `AUDIOFOCUS_LOSS`，
     * 而 ExoPlayer 收到 LOSS 会**自动暂停自己** → 最终只剩一条轨在出声。
     *
     * 多轨共享同一次播放会话，理应**共用一个焦点**：
     * 主轨（第一条）代表整个会话拿焦点，其余轨不参与，
     * 由 [MultiTrackController] 统一接收/响应焦点事件（见 [attachFocusHandling]）。
     *
     * @param primary true = 主轨（参与焦点管理）
     */
    private fun buildPlayer(primary: Boolean, mediaSourceFactory: MediaSource.Factory? = null): ExoPlayer =
        // ★R2/bug C：与单轨同一套构建器（AudioTrack 原生变速变调，绕过 Sonic）
        buildAudioExoPlayer(appContext, handleAudioFocus = primary, mediaSourceFactory = mediaSourceFactory)

    /**
     * ★G-3：载入**流式**多轨（每轨一个自定义 DataSource ⇒ ring buffer 当 WAV 流）。
     *
     * 与 [load] 的区别：每轨构建时注入 `mediaSourceFactory`（把 `stem://` uri 映射到
     * [StemPcmDataSource]），其余（同步/音量/独奏/变速）完全一致。
     *
     * @param items (id, label, uri, dataSourceFactory) —— 顺序即混音顺序
     * @param externalPrimary 主轨复用外部 player（同 [load]）
     */
    fun loadStream(
        items: List<StreamItem>,
        title: String? = null,
        externalPrimary: ExoPlayer? = null,
    ) {
        releaseAll()
        items.forEachIndexed { index, item ->
            val isPrimary = index == 0
            val ownsPlayer = !(isPrimary && externalPrimary != null)
            // 主轨复用外部 player 时，需给它换 mediaSourceFactory（否则它仍按文件解析）。
            val player = if (isPrimary && externalPrimary != null) {
                // ⚠ ExoPlayer 实例无法更换 MediaSourceFactory（只能 Builder 设）。
                //   故主轨（singlePlayer）**必须**在构建时就带“按 scheme 分发”的
                //   ResolvingDataSource（见 PlaybackService）：stem:// → StemPcmDataSource。
                externalPrimary
            } else {
                buildPlayer(primary = isPrimary, mediaSourceFactory = DefaultMediaSourceFactory(item.dataSourceFactory))
            }
            val display = if (isPrimary && !title.isNullOrBlank()) title else item.label
            val mi = MediaItem.Builder()
                .setUri(item.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(display).setArtist("NeuralStem").build(),
                )
                .build()
            player.setMediaItem(mi)
            listeners.forEach { player.addListener(it) }
            if (isPrimary && ownsPlayer) attachFocusHandling(player)
            player.prepare()
            player.playbackParameters = playbackParameters
            tracks[item.id] = Track(item.id, item.label, player, item.uri, ownsPlayer)
        }
        applySoloMute()
        Log.i(TAG, "loadStream: ${tracks.size} streaming track(s) (externalPrimary=${externalPrimary != null})")
    }

    /** ★G-3：流式轨描述。 */
    class StreamItem(
        val id: String,
        val label: String,
        val uri: Uri,
        val dataSourceFactory: DataSource.Factory,
    )

    /**
     * 焦点联动(guide N3)：主轨失焦时，暂停**全部**轨；
     * 恢复播放时，重新同步启动全部轨。
     *
     * 只给主轨挂监 —— 其余轨不持有焦点，不会收到焦点事件。
     */
    private fun attachFocusHandling(primary: ExoPlayer) {
        primary.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // 主轨因失焦被暂停/恢复 → 同步到所有轨
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    Log.i(TAG, "audio focus LOSS → pause all tracks")
                    tracks.values.forEach { it.player.pause() }
                }
            }
        })
    }

    // ---------------- F4.1 同步播放控制 ----------------

    /** 播放:所有实例同一帧内 play() */
    fun play() {
        tracks.values.forEach { it.player.play() }
        startDriftCorrection()
    }

    fun pause() {
        // 暂停时停校正：暂停中 seek 会制造新错位，且恢复时另有对齐逻辑。
        stopDriftCorrection()
        tracks.values.forEach { it.player.pause() }
    }

    fun togglePlayPause() {
        val anyPlaying = tracks.values.any { it.player.isPlaying }
        if (anyPlaying) pause() else play()
    }

    /**
     * F4.1/F4.4:同步 seek。
     *
     * 所有实例在同一帧内 seekTo 同一位置(guide §3.6 对齐要求)。
     */
    fun seekTo(positionMs: Long) {
        tracks.values.forEach { track ->
            val p = track.player
            val target = positionMs.coerceIn(0L, p.duration.coerceAtLeast(0L))
            p.seekTo(target)
        }
    }

    /** F4.4:变速变调全轨联动 */
    fun setPlaybackParameters(params: PlaybackParameters) {
        playbackParameters = params
        tracks.values.forEach { it.player.playbackParameters = params }
    }

    /** 主时钟位置(取任一同构实例;各轨时间轴一致) */
    fun currentPositionMs(): Long =
        tracks.values.firstOrNull()?.player?.currentPosition ?: 0L

    fun durationMs(): Long =
        tracks.values.firstOrNull()?.player?.duration?.coerceAtLeast(0L) ?: 0L

    fun isPlaying(): Boolean = tracks.values.any { it.player.isPlaying }

    fun isReady(): Boolean = tracks.isNotEmpty() && tracks.values.all {
        // STATE_ENDED 也算“已完成准备”（极短文件可能直接跳 ENDED，不接住会永远等）
        it.player.playbackState == Player.STATE_READY ||
            it.player.playbackState == Player.STATE_ENDED
    }

    // ==================== ★对齐起播（2026-11-01 修“音轨错位”）====================

    /**
     * 等**所有轨都 READY** 后，同帧 seek + 同帧 play。
     *
     * ## 为什么不能 load() 后直接 play()（真机实测缺陷）
     * `play()` 对未 READY 的 ExoPlayer 只是把 `playWhenReady` 置 true，
     * 每个实例会在**自己准备好**的那一刻开始出声。
     * 不同 stem 文件（不同码率/时长/解码器）准备完成的时刻不同
     * → **人声/伴奏起播时刻不同 → 可听见的错位**。
     *
     * 实测：4 轨（Demucs）尤为明显——4 个 55MB WAV 解码先后差上百毫秒。
     *
     * ## 为何还要 seekTo(anchor)
     * 边分离边播 / 续播 会带着一个非 0 起点。旧实现把这个 seek 交给了
     * `PlayerViewModel` 的 READY 回调，但它只能 seek **主轨**（mediaController
     * 就是 singlePlayer），跟随轨留在 0 → 两边差了几秒。
     * 现在统一由控制器对**所有轨**下同一位置。
     *
     * @param anchorMs 所有轨对齐到此位置（0 = 从头）
     */
    fun playWhenAllReady(anchorMs: Long = 0L) {
        if (tracks.isEmpty()) return
        startAnchorMs = anchorMs
        cancelPendingStart()
        if (isReady()) {
            alignAndPlay()
            return
        }
        val all = tracks.values.map { it.player }
        all.forEach { it.addListener(alignmentListener) }
        val t = Runnable {
            Log.w(
                TAG,
                "playWhenAllReady: 等待超时（${ALIGN_TIMEOUT_MS}ms）→ 强制起播，可能仍错位",
            )
            alignAndPlay()
        }
        pendingStartTimeout = t
        mainHandler.postDelayed(t, ALIGN_TIMEOUT_MS)
        Log.i(TAG, "playWhenAllReady: 等待 ${all.size} 轨就绪（anchor=${anchorMs}ms）")
    }

    private val alignmentListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isReady()) alignAndPlay()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // 某轨出错就不要死等（否则永远不起播）。降级：把能放的放起来。
            Log.w(TAG, "playWhenAllReady: 某轨出错 ${error.errorCodeName} → 放弃等待")
            alignAndPlay()
        }
    }

    private fun cancelPendingStart() {
        pendingStartTimeout?.let { mainHandler.removeCallbacks(it) }
        pendingStartTimeout = null
        tracks.values.forEach { it.player.removeListener(alignmentListener) }
    }

    /**
     * 同帧：先全部 seek，再全部 play。
     *
     * ## ★为何要先“静音预热”（2026-11-01 真机定位到的真因）
     * 实测（四轨从对齐位置 100000 同时 play）：
     * ```
     * +1s: vocals=101028  drums=100217   ← drums 只走了 217ms
     * +2s: vocals=103031  drums=102239   ← 差 792ms
     * +4s: vocals=107036  drums=106229   ← 差 807ms（不增长）
     * ```
     * 主轨（session 的 singlePlayer）在载入多轨前**已在播单轨**，AudioTrack 是热的；
     * 跟随轨是新建 ExoPlayer，首次 play 要 ~800ms 才真正出声。
     * 差值**恒定不增长** → 是起播延迟，不是速率差异。
     *
     * 更坑的是：直接对跟随轨 `seekTo(anchor)` **无效** —— seek 本身会重新
     * 触发 buffer，又一个 800ms 延迟，于是“校正”变成每 2 秒跳一次音却永远追不上
     * （真机实测校正 105 次，差值仍 890ms）。
     *
     * 所以在**静音**状态下先 play 一小段，让每个 AudioTrack 真正建立/启动，
     * 再 seek 到统一位置、恢复音量、一起 play。
     */
    private fun alignAndPlay() {
        cancelPendingStart()
        val all = tracks.values.map { it.player }
        if (all.isEmpty()) return

        // 预热期间静音：不让用户听到乱响的一小段
        all.forEach { it.volume = 0f }
        all.forEach { it.play() }
        mainHandler.postDelayed({
            // 预热完成：统一位置 → 恢复音量 → 一起起播
            all.forEach { it.seekTo(startAnchorMs) }
            applySoloMute()          // 走音量唯一出口
            all.forEach { it.play() }
            startDriftCorrection()
            Log.i(TAG, "aligned start(warm=${DRIFT_WARMUP_MS}ms): ${all.size} track(s) @ ${startAnchorMs}ms")
        }, DRIFT_WARMUP_MS)
    }

    // ==================== ★漂移校正（2026-11-01 修“播放中错位”）====================

    /**
     * 周期检查各轨位置，落后太多的拉回主轨。
     *
     * ## 为何必须要有（真机实测）
     * 即使起播完全对齐，**播放过程中仍会间歇性错位**：连续采样得到的轨间差是
     * `10ms → 1022ms → 18ms → 9ms → 1010ms` 这种交替形态（非单调、无 seek 事件）。
     * 这是 **AudioTrack underrun**：某个实例一时拿不到数据，AudioTrack 停等，
     * 恢复后它的位置就永久落后了一截。
     *
     * 4 个独立 ExoPlayer 各自渲染，在低端机上几乎必然遇到，
     * 靠“起播时对齐”无法避免 —— 只能在运行中持续拉起。
     *
     * ## 阈值为何是 150ms
     * - 人耳对“人声/伴奏”这种高度相关信号的错位很敏感，~50ms 就能听出。
     * - 但 re-seek 本身会造成一个微小的跳音，阈值太小会**频繁跳**（更差）。
     * - 150ms 是“能听出”与“去抖”之间的折中。
     *
     * ## 为何只拉跟随轨
     * 主轨是 session 的那个 player（通知/进度条都看它）。去动主轨会让
     * UI 进度往回跳。只把落后的跟随轨 seek 到主轨位置，用户无感。
     */
    private fun startDriftCorrection() {
        stopDriftCorrection()
        val r = object : Runnable {
            override fun run() {
                correctDriftOnce()
                mainHandler.postDelayed(this, DRIFT_CHECK_INTERVAL_MS)
            }
        }
        driftRunnable = r
        mainHandler.postDelayed(r, DRIFT_CHECK_INTERVAL_MS)
        Log.i(TAG, "driftCorrection: 已启动（每 ${DRIFT_CHECK_INTERVAL_MS}ms，阈值 ${DRIFT_THRESHOLD_MS}ms）")
    }

    private fun stopDriftCorrection() {
        driftRunnable?.let { mainHandler.removeCallbacks(it) }
        driftRunnable = null
    }

    // ==================== ★校正统计（2026-11-01 用户要求：必须能量化）====================

    /**
     * 校正统计。
     *
     * ## 为何必须统计“seek 后到底有没有变小”（不是只记“我发了 seek”）
     * 之前只记了“本期纠正 N 轨”，实际发生的是：校正 105 次、轨间差仍 890ms。
     * 因为 **seekTo 会重新触发 buffer**，跟随轨又要等 ~800ms 才出声。
     * 所以必须记录 seek **前**的差值，并在一段延迟后回采**后**的差值，
     * 才能判断 seek “有效”还是“反而更差”。
     */
    internal data class DriftStats(
        var ticks: Int = 0,
        var corrections: Int = 0,
        var sumAbsDelta: Long = 0L,
        var maxAbsDelta: Long = 0L,
        var lastDeltasMs: Map<String, Long> = emptyMap(),
        /** seek 前 → 300ms 后的差值回采（正值 = 仍未追上） */
        var lastSeekBeforeMs: Long = 0L,
        var lastSeekAfterMs: Long = 0L,
        var seekIneffective: Int = 0,
    )

    internal val driftStats = DriftStats()

    /** 格式化当前统计（`mtpos` / 退出时输出）。 */
    internal fun driftStatsLine(): String {
        val s = driftStats
        val avg = if (s.ticks > 0) s.sumAbsDelta / s.ticks else 0L
        return "driftStats: ticks=${s.ticks} corrections=${s.corrections} " +
            "avgAbs=${avg}ms maxAbs=${s.maxAbsDelta}ms " +
            "seekBefore=${s.lastSeekBeforeMs}ms seekAfter=${s.lastSeekAfterMs}ms " +
            "ineffective=${s.seekIneffective}"
    }

    /** 执行一次校正（可包内直调，便于单测/诊断）。 */
    internal fun correctDriftOnce() {
        val list = tracks.values.toList()
        if (list.size < 2) return
        // 任何一轨没在放就别动（暂停/缓冲中 seek 会制造新问题）
        if (list.any { !it.player.isPlaying }) return
        val anchor = list.first()
        val anchorPos = anchor.player.currentPosition
        if (anchorPos <= 0L) return

        // ---- 统计：先记“看到的差值” ----
        val s = driftStats
        s.ticks++
        s.lastDeltasMs = list.drop(1).associate { it.id to (anchorPos - it.player.currentPosition) }
        for (d in s.lastDeltasMs.values) {
            val a = kotlin.math.abs(d)
            s.sumAbsDelta += a
            if (a > s.maxAbsDelta) s.maxAbsDelta = a
        }
        Log.d(TAG, "driftTick: anchor=$anchorPos deltas=${s.lastDeltasMs}")

        var corrected = 0
        for (t in list.drop(1)) {
            val pos = t.player.currentPosition
            val delta = anchorPos - pos
            if (kotlin.math.abs(delta) > DRIFT_THRESHOLD_MS) {
                t.player.seekTo(anchorPos)
                corrected++
                s.corrections++
                s.lastSeekBeforeMs = delta
                // ★关键：seek 后回采 —— 判断它到底有没有用
                val player = t.player
                val id = t.id
                mainHandler.postDelayed({
                    val after = anchorPos - player.currentPosition
                    s.lastSeekAfterMs = after
                    val improved = kotlin.math.abs(after) < kotlin.math.abs(delta)
                    if (!improved) s.seekIneffective++
                    Log.w(
                        TAG,
                        "drift: $id seek 前=${delta}ms 后=${after}ms " +
                            (if (improved) "→ 有效" else "→ ⚠️无效（seek 重新触发了 buffer）"),
                    )
                }, 300L)
                Log.w(TAG, "drift: ${t.id} 落后 ${delta}ms（pos=$pos anchor=$anchorPos）→ 拉回")
            }
        }
        if (corrected > 0) Log.w(TAG, "drift: 本轮纠正 $corrected 轨 | ${driftStatsLine()}")
    }

    // ---------------- F4.2/F4.3 音量 ----------------

    /**
     * **唯一**的音量计算入口(F4.2 + F4.3)。
     *
     * 优先级:独奏 > 静音 > 设定音量。
     * 所有会改变音量的操作(音量改动 / 静音切换 / 独奏切换 / 新轨载入)
     * 都必须最终调到这里,否则会出现「某些操作后音量不对」的 bug。
     */
    private fun applySoloMute() {
        val solo = soloTrackId
        tracks.values.forEach { track ->
            val target = when {
                solo != null -> if (track.id == solo) track.volume else 0f
                track.muted -> 0f
                else -> track.volume
            }
            track.applyEffectiveVolume(target)
            Log.i(
                TAG,
                "volume[${track.id}]: target=${"%.2f".format(target)} " +
                    "(vol=${"%.2f".format(track.volume)} muted=${track.muted} solo=${solo ?: "-"})",
            )
        }
    }

    /** 重新应用音量(改 volume / muted 后调用,以叠加独奏规则) */
    fun refreshVolumes() = applySoloMute()

    /**
     * 已注册的监听器。
     *
     * 修正(真机反馈「伴奏没声音」)：原先 [addListener] 只给**当前已存在**的轨
     * 挂监听，而 `load()` 会重建全部轨 —— 在 load 前注册就扑空，在 load 后注册
     * 则旧轨泄漏监听。改为保存到列表，`load()` 时给新建的轨逐一挂上。
     */
    private val listeners = mutableListOf<Player.Listener>()

    /** 新增监听(对已存在与后续新建的轨均生效) */
    fun addListener(listener: Player.Listener) {
        listeners += listener
        tracks.values.forEach { it.player.addListener(listener) }
    }

    fun removeListener(listener: Player.Listener) {
        listeners -= listener
        tracks.values.forEach { it.player.removeListener(listener) }
    }

    private fun releaseAll() {
        stopDriftCorrection()
        cancelPendingStart()
        // 只 release 自己建的 player；主轨若是外部传入（singlePlayer），不能动。
        tracks.values.forEach { if (it.ownsPlayer) it.player.release() }
        tracks.clear()
        soloTrackId = null
    }

    override fun close() = releaseAll()

    private companion object {
        const val TAG = "MultiTrack"

        /**
         * 等待所有轨 READY 的**上限**。
         *
         * 本地 WAV 通常几十毫秒就 READY；这个上限只用于“某轨卡死/出错”时
         * 不让用户完全听不到声音。超过就降级起播（可能仍有错位，但至少有声）。
         */
        const val ALIGN_TIMEOUT_MS = 3_000L

        /** 漂移校正检查周期。2s 兼顾“及时拉起”与“不要过度打扰”。 */
        const val DRIFT_CHECK_INTERVAL_MS = 2_000L

        /**
         * 起播前的静音预热时长。
         *
         * 实测跟随轨首次 play 要 ~800ms 才真正出声（AudioTrack 建立），
         * 所以预热必须 ≥ 这个量级。1000ms 留余量。
         */
        const val DRIFT_WARMUP_MS = 1_000L

        /**
         * 触发校正的轨间差阈值（ms）。
         *
         * 人耳对强相关信号（人声/伴奏）的错位 ~50ms 就能听出；
         * 但 re-seek 本身会造成微小跳音，阈值太小反而更差。150ms 是折中。
         */
        const val DRIFT_THRESHOLD_MS = 150L
    }
}
