package pub.miku.neuralstem.player

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.Closeable
import java.io.File

/**
 * 多轨播放控制器 —— **单 AudioTrack + 自研混音**（2026-11-01 重构）。
 *
 * ## 为什么推倒重来（真机实测的架构结论）
 * 旧实现是「每条 stem 一个 ExoPlayer」，于是有 N 个独立 AudioTrack。实测（BF00A）：
 *
 * | 问题 | 实测证据 |
 * |---|---|
 * | 起播错位 | 同时播放后 `+1s: vocals=101028 drums=100217` → 差 800ms，且恒定不收敛 |
 * | 校正无效 | seek 会**重新触发 buffer**，校正 105 次后差值仍 890ms（越修越乱）|
 * | 中途暂停 | 4 条并发 AudioTrack 轮流 underrun，`isPlaying` 间歇变 false |
 * | 遥测失真 | `currentPosition` 是**媒体时钟**，各轨缓冲深度不同（3776 vs 4104 帧），位置说对齐、听感仍错位 |
 *
 * ## 现在的模型（DAW / DJ / 游戏引擎同一做法）
 * **只有一个时钟**：一个 [AudioTrack]，一个渲染线程，
 * 按同一个 `framePos` 从所有 stem 读 PCM、乘增益、求和、写入。
 *
 * ```
 * StemMixer (1× AudioTrack)
 *   ├── FileStemSource(vocals)
 *   ├── FileStemSource(drums)
 *   └── ...   同一 framePos，同一循环
 * ```
 *
 * 轨间不存在漂移这个概念 —— 它们本来就是同一个 `framePos` 读出来的。
 *
 * ## 对外 API 保持兼容
 * `trackList` / `volume` / `muted` / `solo` / `play` / `pause` / `seekTo` /
 * `currentPositionMs` / `durationMs` / `isPlaying` 语义不变，
 * 所以 `PlaybackService` / `PlayerViewModel` 的调用点几乎不用改。
 */
class MultiTrackController(private val context: Context) : Closeable {

    /**
     * 一条轨的**元信息**（不再持有 ExoPlayer）。
     *
     * 保留 `uri` 供 UI/日志展示与快照；实际数据由 [StemMixer] 内部的 [StemSource] 提供。
     */
    class Track(
        val id: String,
        val label: String,
        val uri: Uri,
    ) {
        /** 用户设定音量 0..1。 */
        var volume: Float = 1f
            set(value) {
                field = value.coerceIn(0f, 1f)
            }

        /** 用户静音。 */
        var muted: Boolean = false

        /** 本轨生效增益（音量 × 静音 × 独奏）。由控制器计算。 */
        internal var effectiveVolume: Float = 1f
    }

    /** 流式载入项（边分离边播）。 */
    data class StreamItem(
        val id: String,
        val label: String,
        val uri: Uri,
    )

    private val tracks = LinkedHashMap<String, Track>()

    /** 独奏轨 id（null = 无独奏）。 */
    @Volatile
    var soloTrackId: String? = null
        private set

    private var mixer: StemMixer? = null

    val trackList: List<Track> get() = tracks.values.toList()
    val isEmpty: Boolean get() = tracks.isEmpty()

    // ==================== 载入 ====================

    /**
     * 载入**磁盘 WAV** 版多轨（分离完成后）。
     *
     * @param items (id, label, uri) —— uri 必须是 `file://` 指向 WAV
     * @param title 曲目名（仅日志/UI 用；本控制器不持有 session，通知标题由 Service 处理）
     */
    fun load(
        items: List<Triple<String, String, Uri>>,
        title: String? = null,
    ) {
        releaseAll()
        val sources = ArrayList<Pair<String, StemSource>>(items.size)
        items.forEach { (id, label, uri) ->
            val f = File(requireNotNull(uri.path) { "$id: uri 无 path" })
            if (!f.exists()) {
                Log.w(TAG, "load: $id 文件不存在 ${f.absolutePath}，跳过")
                return@forEach
            }
            tracks[id] = Track(id, label, uri)
            sources += id to FileStemSource(f)
        }
        if (sources.isEmpty()) {
            Log.w(TAG, "load: 没有任何有效 stem")
            return
        }
        startMixer(sources, title)
    }

    /**
     * 载入**边分离边播**版多轨（ring buffer）。
     *
     * 与 [load] 的区别：数据还在增长，读到未分离区会短暂返回 0（渲染线程会重试）。
     *
     * @param ringStartFrame ring buffer 第 0 帧对应的原曲绝对帧
     */
    fun loadStream(
        items: List<StreamItem>,
        title: String? = null,
    ) {
        releaseAll()
        // buffer 从边分离会话取（与 StemPcmDataSource 同一来源，保持单一真源）
        val session = pub.miku.neuralstem.spike.EdgeSeparationSession.get()
        val ringStart = session.startFrame.toLong()
        // ★P2：流式源只知“已写水位线”，seek 应允许到整曲；闸门负责未分离区等待。
        declaredDurationMs = if (session.totalFrames > 0 && session.sampleRate > 0) {
            session.totalFrames.toLong() * 1000L / session.sampleRate
        } else null
        val sources = ArrayList<Pair<String, StemSource>>(items.size)
        for (item in items) {
            val host = item.uri.host ?: item.id
            val buf = if (host == "accompaniment") session.accompBuffer else session.vocalsBuffer
            if (buf == null) {
                Log.w(TAG, "loadStream: host=$host 没有 ring buffer，跳过 ${item.id}")
                continue
            }
            tracks[item.id] = Track(item.id, item.label, item.uri)
            sources += item.id to RingBufferStemSource(
                buffer = buf,
                sampleRate = session.sampleRate,
                ringStartFrame = ringStart,
            )
        }
        if (sources.isEmpty()) {
            Log.w(TAG, "loadStream: 没有任何有效 stem")
            return
        }
        startMixer(sources, title)
    }

    private fun startMixer(sources: List<Pair<String, StemSource>>, title: String?) {
        val sr = sources.first().second.sampleRate
        val ch = sources.first().second.channels
        // ★顺序很重要：先把 effectiveVolume 算出来，再建 mixer。
        //   否则第一轮混音读到 gain=0（字段默认值），会整体无声。
        applySoloMute()
        // 增益回调：混音时实时读（音量/静音/独奏改动立即生效，不用重启 mixer）
        val m = StemMixer(sampleRate = sr, channels = ch) { id ->
            tracks[id]?.effectiveVolume ?: 0f
        }
        m.load(sources)
        mixer = m
        Log.i(
            TAG,
            "loaded ${tracks.size} track(s) [${tracks.keys.joinToString()}] " +
                "title=${title ?: "-"} ${sr}Hz/${ch}ch (单 AudioTrack 混音)",
        )
    }

    // ==================== 播放控制 ====================

    fun play() {
        applySoloMute()
        mixer?.play()
    }

    fun pause() {
        mixer?.pause()
    }

    fun togglePlayPause() {
        if (isPlaying()) pause() else play()
    }

    /**
     * ★多轨变速变调（2026-11-06）。
     *
     * @param speed 速度倍率
     * @param pitchRate 音高倍率（2^(半音/12)）
     */
    fun setSpeedPitch(speed: Float, pitchRate: Float) {
        mixer?.setSpeedPitch(speed, pitchRate)
        Log.i(TAG, "setSpeedPitch speed=$speed pitchRate=$pitchRate")
    }

    /**
     * 同步 seek。
     *
     * ## 为何现在“同步”是天然的
     * 旧实现要给 N 个 ExoPlayer 各下 seekTo（还是会有微差）；
     * 现在只有一个 `framePos`，改它即可 —— **所有轨必然在同一帧**。
     */
    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0L, durationMs().coerceAtLeast(0L))
        mixer?.seekTo(target)
        Log.i(TAG, "seekTo ${target}ms")
    }

    fun currentPositionMs(): Long = mixer?.positionMs ?: 0L

    fun durationMs(): Long = declaredDurationMs ?: mixer?.durationMs ?: 0L

    fun isPlaying(): Boolean = mixer?.isPlaying ?: false

    /** 混音器是否已到曲尾（变速时比门面先到）。 */
    fun isEnded(): Boolean = mixer?.ended ?: false

    fun isReady(): Boolean = mixer != null && tracks.isNotEmpty()

    /** 混音输出的 AudioSessionId（供 EQ 挂载；0 = 未就绪）。 */
    fun audioSessionId(): Int = mixer?.audioSessionId ?: 0

    /**
     * 兼容旧调用点：mixer 是同步就绪的，直接起播。
     *
     * @param anchorMs 起播位置（边播 carry-over / 续播用）
     */
    fun playWhenAllReady(anchorMs: Long = 0L) {
        if (anchorMs > 0L) mixer?.seekTo(anchorMs)
        play()
    }

    // ==================== 音量 / 静音 / 独奏 ====================

    fun setVolume(trackId: String, volume: Float) {
        tracks[trackId]?.volume = volume
        applySoloMute()
    }

    fun toggleMute(trackId: String) {
        tracks[trackId]?.let { it.muted = !it.muted }
        applySoloMute()
    }

    fun toggleSolo(trackId: String) {
        soloTrackId = if (soloTrackId == trackId) null else trackId
        applySoloMute()
    }

    fun refreshVolumes() = applySoloMute()

    /**
     * **唯一**的增益计算入口。
     *
     * 优先级：独奏 > 静音 > 设定音量。
     * 结果写进 `Track.effectiveVolume`，由 [StemMixer] 的 gainProvider 在混音时读取
     * —— 所以改动**立即生效**，不需要重启渲染线程。
     */
    private fun applySoloMute() {
        val solo = soloTrackId
        tracks.values.forEach { t ->
            t.effectiveVolume = when {
                solo != null && t.id != solo -> 0f     // 有独奏且不是本轨
                t.muted -> 0f
                else -> t.volume
            }
            Log.d(TAG, "gain[${t.id}]=${t.effectiveVolume} (vol=${t.volume} muted=${t.muted} solo=${solo ?: "-"})")
        }
    }

    // ==================== 监听（保持旧签名，Service 已注册）====================

    private val listeners = mutableListOf<androidx.media3.common.Player.Listener>()

    fun addListener(listener: androidx.media3.common.Player.Listener) {
        listeners += listener
    }

    fun removeListener(listener: androidx.media3.common.Player.Listener) {
        listeners -= listener
    }

    // ==================== 释放 ====================

    /** 流式模式声明的整曲时长；null = 用 mixer 已写范围（文件模式）。 */
    private var declaredDurationMs: Long? = null

    private fun releaseAll() {
        runCatching { mixer?.close() }
        mixer = null
        tracks.clear()
        soloTrackId = null
        declaredDurationMs = null
    }

    override fun close() = releaseAll()

    companion object {
        private const val TAG = "MultiTrack"
    }
}
