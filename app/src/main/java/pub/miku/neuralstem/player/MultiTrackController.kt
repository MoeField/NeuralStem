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
import androidx.media3.exoplayer.ExoPlayer
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
    private fun buildPlayer(primary: Boolean): ExoPlayer =
        ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ primary,
            )
            .build()

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
    }

    fun pause() {
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
        it.player.playbackState == Player.STATE_READY
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
        // 只 release 自己建的 player；主轨若是外部传入（singlePlayer），不能动。
        tracks.values.forEach { if (it.ownsPlayer) it.player.release() }
        tracks.clear()
        soloTrackId = null
    }

    override fun close() = releaseAll()

    private companion object {
        const val TAG = "MultiTrack"
    }
}
