@file:OptIn(UnstableApi::class)

package pub.miku.neuralstem.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import pub.miku.neuralstem.playlist.PlaylistStore
import pub.miku.neuralstem.settings.SettingsRepository
import kotlin.math.abs

/**
 * Task 1 基础播放器(guide F0.1–F0.4, N3)。
 *
 * 设计说明:
 * - V2(N4)起:单轨 player 由 [PlaybackService] 持有,本类经 [MediaController] 下发命令;
 *   多轨仍为 [MultiTrackController](方案 A:每 stem 一个实例)。
 * - 进度轮询:100ms 间隔 [androidx.media3.common.Player.Listener] 之外的 polling,
 *   因为 ExoPlayer 没有进度推送 API;100ms 是 SeekBar 流畅度与 CPU 的平衡点。
 * - 音频焦点(N3):`handleAudioFocus=true` 让 ExoPlayer 代管 —— 抢占时自动暂停,
 *   焦点归还时按 `playWhenReady` 恢复。Task 1 用最简方案,后续需要自定义行为再换手动管理。
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    /** UI 观察的单一状态。用 data class 避免 N 个碎片 StateFlow 的组合样板。 */
    data class PlayerState(
        val fileName: String = "",
        val isPlaying: Boolean = false,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val isReady: Boolean = false,
        /** ★ UI 门控用：是否已**载入**媒体（doLoad 后即 true，不依赖 READY）。
         *  为何不用 isReady：isReady 是“真正可播”的瞬时状态，一旦加载失败/超时
         *  就永远为 false → 会把整块 UI 一刀切掉（白屏）。UI 应“载入即显”。 */
        val hasMedia: Boolean = false,
        /** ★ 播放/加载错误信息（null = 无）；UI 应展示而非白屏。 */
        val error: String? = null,
        /** 波形包络(Task 2);null = 还没提取完 */
        val waveform: WaveformData? = null,
        /** Task 3:速度倍率 0.5–2.0 */
        val speed: Float = 1f,
        /** Task 3:音高,半音 -12..12 */
        val pitchSemitones: Float = 0f,
        /** Task 4.4:是否处于多轨模式 */
        val isMultiTrack: Boolean = false,
        /** Task 4.4:各轨 UI 状态(多轨模式下非空) */
        val tracks: List<TrackUiState> = emptyList(),
        /** M2-D:EQ 是否已开启 */
        val eqEnabled: Boolean = false,
        /** M2-D:硬件是否支持 EQ(不支持时 UI 应置灰) */
        val eqAvailable: Boolean = false,
        /** U3:分离开关（true=播多轨；false=播原曲） */
        val separationOn: Boolean = false,
        /** U3:是否已有可用分离结果（无则开开关需先分离） */
        val stemsAvailable: Boolean = false,
        /** U4:EQ 各段中心频率(Hz) */
        val eqFreqsHz: IntArray = IntArray(0),
        /** U4:EQ 各段当前增益(mB) */
        val eqLevelsMb: ShortArray = ShortArray(0),
        /** UI-V:内嵌封面（黑胶内圈）；null = 无（UI 走曲名环） */
        val cover: ImageBitmap? = null,
        /** ★3.3:当前播放模式（顺序/循环歌单/单曲循环）——循环按钮显示。 */
        val repeatMode: SettingsRepository.RepeatMode = SettingsRepository.RepeatMode.LOOP_ALL,
        /** 歌单/切歌：当前歌单 id（null = 尚未绑定歌单）。播放的曲必须属此歌单。 */
        val playlistId: String? = null,
        /** 当前歌单名（UI 显示）。 */
        val playlistName: String = "",
        /** 当前歌单类型：true = 文件夹歌单（此时隐藏“导入音频”按钮）。 */
        val playlistIsFolder: Boolean = false,
        /** 当前播放曲的 uri（用于歌单页**按“曲目身份”高亮**，
         *  排序/删其它曲后仍正确；不用 index 以免错位）。 */
        val currentUri: String? = null,
        /** 当前曲在歌单中的下标（0-based）。 */
        val trackIndex: Int = -1,
        /** 当前歌单曲目总数。 */
        val trackCount: Int = 0,
    )

    /** Task 4.4:单条轨的 UI 状态(F4.2/F4.3) */
    data class TrackUiState(
        val id: String,
        val label: String,
        val volume: Float = 1f,
        val muted: Boolean = false,
        val solo: Boolean = false,
    )

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /**
     * UI 重做 · R4：**高频进度独立流**。
     *
     * 为何单独：`positionMs` 每 200ms 变一次，若放进 [PlayerState] 会**整体换对象**
     * → `PlayerScreen` 整页重组 → seek 时“闪”。拆出后只有进度条/转盘订阅它。
     * 同时仍写回 state（兼容旧读取），但页面应优先订阅本流。
     */
    data class Progress(val positionMs: Long = 0L, val durationMs: Long = 0L)
    private val _progress = MutableStateFlow(Progress())
    val progressFlow: StateFlow<Progress> = _progress.asStateFlow()
    val progress: Progress get() = _progress.value

    /**
     * ★ UI 时钟锚点：供 UI 用“本地单调时钟外推”得到**平滑且不漂移**的时间轴。
     *
     * 背景：`Player.currentPosition` 只有 ~10Hz 且**值不均匀**（从 AudioTrack 播放头推算）；
     * 用它直接驱动数字/动画会“一卡一跳”。
     *
     * 方案（与官方 `PlaybackStatsListener.getMediaTimeMsAtRealtimeMs` 同思路）：
     * `pos(now) = positionMs + (now - realtimeMs) × speed`；锚点仅在“漂移过大/暂停/变速/
     * 首帧”时重设，其余交给 UI 外推（平滑、不受 10Hz 限制）。
     */
    data class UiClock(
        val positionMs: Long,
        val realtimeMs: Long,
        val speed: Float,
        val isPlaying: Boolean,
        val durationMs: Long,
    ) {
        /** 在本地时刻 [nowRealtimeMs] 外推出的媒体位置。
         *  注：“重锚突变”的平滑不再在这里做，而是由 UI 侧 [rememberSmoothPosition] 逐帧摊平。 */
        fun extrapolate(nowRealtimeMs: Long): Long {
            if (!isPlaying) return positionMs
            val dt = nowRealtimeMs - realtimeMs
            return positionMs + (dt * speed).toLong()
        }
    }

    private val _anchor = MutableStateFlow<UiClock?>(null)
    val anchorFlow: StateFlow<UiClock?> = _anchor.asStateFlow()

    /** 波形提取器(Task 2);scope 用 VM 的,切歌自动取消上次 */
    private val waveformExtractor = WaveformExtractor(viewModelScope).apply {
        contextProvider = { getApplication<Application>() }
    }

    /** UI-V：封面提取器（黑胶内圈）；同款异步/可取消。 */
    private val coverExtractor = CoverExtractor(viewModelScope).apply {
        contextProvider = { getApplication<Application>() }
    }

    // ---- 歌单/切歌：歌单为播放队列（详见 playlist/）----
    private val playlistStore = PlaylistStore(app)
    private val settingsRepo = SettingsRepository(app)

    /** 当前歌单 id（= 播放队列来源）。 */
    @Volatile
    private var currentPlaylistId: String? = null

    /**
     * Task 3:变速变调(guide F2.x)。
     * ExoPlayer 内部(DefaultAudioSink)自带 SonicAudioProcessor:
     * `setPlaybackParameters(speed, pitch)` 播放中调用即实时生效,不中断(F2.3),
     * 无需手动挂 AudioProcessor 链。速度与音高独立(F2.1/F2.2)。
     *
     * V2(guide N4):player 不再由本 VM 创建,而是连接 [PlaybackService] 的
     * MediaSession 得到的 [MediaController] —— 播放器生命周期属于 Service,
     * 因此切后台/锁屏仍继续播放,并有系统媒体通知。
     */
    @Volatile
    private var mediaController: MediaController? = null

    /**
     * M3-P3:EQ 已搬进 [PlaybackService]（多轨 player 在那里，能拿到会话）。
     * 本 VM 不再持 EqController。
     */

    /**
     * 待执行的载入请求:若 [loadAndPlay] 在 [mediaController] 连接完成前被调用,
     * 先存起来,连接就绪后重放。
     *
     * ⚠️ 为何需要它:MediaController 是**异步**连接的(实测连接耗时 ~120ms),
     * 而 SAF 导入/自动化入口可能在 onCreate 里就立即调 [loadAndPlay]。
     * 早期实现里这个窗口内的请求会被静默丢弃(“controller not connected yet”)
     * —— 现象就是「点导入没反应」。除靠 state 变化驱动的调用点外,
     * 这里用一次性 pending 保证不丢。
     */
    private var pendingLoad: ((Player) -> Unit)? = null

    /** 连接前收到的多轨命令（坑 40：异步连接），连上后重放。 */
    private val pendingMtCommands = mutableListOf<Pair<String, Bundle>>()

    /** ★ 变速变调防抖：只保留最后一次待下发参数（见 [applyPlaybackParameters]）。 */
    @Volatile
    private var pendingParams: PlaybackParameters? = null
    private var paramDebounceJob: Job? = null

    /** ★ seek 目标（毫秒）；非 null = 锁定中（防止 seek 后进度回跳）。见轮询。 */
    @Volatile
    private var pendingSeekMs: Long? = null

    /** ★ 上次显示的进度（用于单调化：非 seek 期间只允许前进）。见轮询。 */
    @Volatile
    private var lastShownMs: Long = -1L

    /**
     * 对外暴露的 player(单轨)。连接完成前为 null。
     * 类型为 [Player] —— [MediaController] 与 ExoPlayer 均实现它,
     * 所以既有调用点无需改动。
     */
    val player: Player? get() = mediaController

    /** 连接 Service 的 session。listen 回调用于把所有 UI 监听挂上去。 */
    private fun connectToService() {
        val token = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlaybackService::class.java),
        )
        val future = MediaController.Builder(getApplication(), token)
            // M3/P2:media3 的 MediaController.Listener 只能在构建时注册
            // （addListener 只收 Player.Listener）—— 用它收多轨状态快照
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                    applyTracksSnapshot(extras.getString(MultiTrackSession.KEY_TRACKS_JSON))
                    applyEqSnapshot(extras.getString(MultiTrackSession.KEY_EQ_JSON))
                }
            })
            .buildAsync()
        future.addListener({
            val c = future.get()
            mediaController = c
            attachPlayerListeners(c)
            Log.i(TAG, "MediaController connected to PlaybackService")
            // 重放连接前收到的载入请求
            val replay = pendingLoad
            pendingLoad = null
            replay?.invoke(c)
            // ★ 恢复：Activity 重建（退桌面再进）后，Service 可能仍在播；
            //   新 VM 的 state 是初始值 → UI 会显示“尚未导入”。从 Session 同步回来。
            //   若有待处理载入（刚导入），则不恢复（避免覆盖新载入）。
            if (replay == null) restoreFromSession(c)
            // M3/P2:重放连接前收到的多轨命令
            flushMtCommands(c)
        }, MoreExecutors.directExecutor())
    }

    /**
     * 从 [PlaybackService] 的会话恢复 UI 状态（Activity 重建 / 退桌面再进）。
     *
     * 为何需要：`PlayerViewModel` 与 Activity 同生命周期（重建即丢状态），
     * 而 `PlaybackService` 长存（可能仍在播）→ 新 VM 的 state 是初始值，
     * UI 会错误地显示“尚未导入音频”，但声音又在响。
     *
     * 做法：会话 player 已有媒体时，把曲名/播放态/时长/队列信息同步回 state，
     * 并重新触发波形/封面提取。
     */
    private fun restoreFromSession(p: Player) {
        val item = p.currentMediaItem ?: return
        val name = item.mediaMetadata.title?.toString()?.takeIf { it.isNotEmpty() }
            ?: item.localConfiguration?.uri?.lastPathSegment ?: ""
        val uri = item.localConfiguration?.uri
        Log.i(TAG, "restoreFromSession: name=$name playing=${p.isPlaying} count=${p.mediaItemCount}")

        // 尝试在当前歌单里定位这首（用于显示“当前歌单 X/Y”与高亮）
        val uriStr = uri?.toString()
        val lists = playlistStore.load()
        val hit = lists.firstOrNull { l -> uriStr != null && l.tracks.any { it.uri == uriStr } }
        val idx = hit?.tracks?.indexOfFirst { it.uri == uriStr } ?: -1
        if (hit != null) currentPlaylistId = hit.id

        _state.value = _state.value.copy(
            fileName = name,
            currentUri = uriStr,
            hasMedia = true,
            isPlaying = p.isPlaying,
            isReady = p.playbackState == Player.STATE_READY,
            durationMs = p.duration.coerceAtLeast(0L),
            playlistId = hit?.id,
            playlistName = hit?.name ?: "",
            playlistIsFolder = hit?.type == PlaylistStore.ListType.FOLDER,
            trackIndex = idx,
            trackCount = hit?.tracks?.size ?: p.mediaItemCount,
            separationOn = false,
            error = null,
        )
        // 重新提取波形/封面（新 VM 里是空的）
        uri?.let {
            waveformExtractor.extractAsync(AudioSource.MediaUri(it))
            coverExtractor.extractAsync(it)
        }
    }

    /** 把所有 UI 监听挂到 session 的 player 上(波形、播放态、错误)。 */
    private fun attachPlayerListeners(p: Player) {
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // ★v2：session 的 player = singlePlayer，多轨时它**就是主轨**，
                //   主轨的 isPlaying 即整组的播放态（跟随轨由 Service 镜像）。
                //   故**单/多轨均由此驱动**——旧代码多轨时跳过更新，导致
                //   “切多轨后播放/暂停按钮卡在旧态”（真机 E2E 抽到）。
                if (_state.value.isPlaying != isPlaying) {
                    _state.value = _state.value.copy(isPlaying = isPlaying)
                }
                // ★ 健康播放后重置 AudioSink 恢复额度，避免“早一次偶发超时”把上限吃光
                if (isPlaying) audioSinkRecoveryCount = 0
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // ★ 播放头发生**不连续**（seek / 重播到头）→ 重置进度单调化基准，
                //   否则 _progress 会卡在旧值（曾出现：播到末尾再点播放→不转）。
                lastShownMs = newPosition.positionMs
                pendingSeekMs = null
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // ★★ 根因修复：“闪”的来源。
                //   `isReady` 是**整块播放 UI 的开关**（`if (state.isReady)`）。
                //   而 seek 会让 ExoPlayer 经历 READY→BUFFERING→READY；
                //   若直接 `isReady = (state==READY)`，BUFFERING 那一下会把整块 UI
                //   **移除再重建** → 可见“闪”（而顶部按钮在 if 之外，不闪）。
                //   ⇒ 改成 **latch**：一旦 READY 就保持，只有 IDLE（停止/错误）才清。
                val ready = playbackState == Player.STATE_READY
                val dur = p.duration
                val cur = _state.value
                val newReady = when (playbackState) {
                    Player.STATE_IDLE -> false
                    else -> cur.isReady || ready      // latch
                }
                // ★ duration 仅在“有效”时才覆盖（seek/缓冲期 p.duration 会短暂为
                //   C.TIME_UNSET/0），否则会把 duration 写成 0 → 多余重组/闪。
                val newDur = if (dur > 0) dur else cur.durationMs
                if (cur.isReady != newReady || cur.durationMs != newDur) {
                    _state.value = cur.copy(isReady = newReady, durationMs = newDur)
                }
                if (ready && !cur.isMultiTrack) {
                    Log.i(TAG, "controller READY (session=${p.audioSessionId})")
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // ★ 切歌清理：波形/封面重提取；进度/锚点重置（避免旧状态残留）。
                lastShownMs = -1L
                pendingSeekMs = null
                _progress.value = Progress(0L, _progress.value.durationMs)
                _state.value = _state.value.copy(waveform = null)
                mediaItem?.localConfiguration?.uri?.let { uri ->
                    waveformExtractor.extractAsync(AudioSource.MediaUri(uri))
                    coverExtractor.extractAsync(uri)
                }
                // ★ 标题：**无条件更新**（只要有 metadata.title 就更新）。
                //   曾把标题更新包在 `if (playlistId != null)` 里 → 未选中歌单时切歌标题不更新。
                mediaItem?.mediaMetadata?.title?.toString()?.takeIf { it.isNotEmpty() }?.let { t ->
                    _state.value = _state.value.copy(fileName = t)
                }
                // ★ 当前曲身份（uri）——高亮用（排序/删其它曲后仍正确）。
                mediaItem?.localConfiguration?.uri?.toString()?.let { u ->
                    _state.value = _state.value.copy(currentUri = u)
                }
                // ★ 歌单：更新当前曲下标/总数（队列 = 当前歌单）
                val idx = p.currentMediaItemIndex
                if (idx >= 0) {
                    _state.value = _state.value.copy(
                        trackIndex = idx,
                        trackCount = if (_state.value.playlistId != null) p.mediaItemCount
                        else _state.value.trackCount,
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // ★ 记录错误（UI 展示，不白屏）。旋转/后台等原因不应重播（见 loadAndPlay 幂等）。
                val msg = "播放失败: ${error.errorCodeName}"
                Log.e(TAG, msg, error)
                _state.value = _state.value.copy(error = msg)

                // ★ 致命播放错误（如 ERROR_CODE_TIMEOUT）的兜底恢复。
                //   注：ERROR_CODE_TIMEOUT 实为 media3 StuckPlayerDetector 发出的
                //   （“isPlaying 但 currentPosition 长时间不推进”），非 AudioSink 超时。
                //   见 .critical_steps.md 坑 48。当前已改用 AudioTrack 原生路径（不卡），
                //   本恢复作为兵底：重设同一 mediaItem + seek 回原位即可重建 pipeline。
                // ★ 致命播放错误（如 ERROR_CODE_TIMEOUT）的兜底恢复。
                //   注：ERROR_CODE_TIMEOUT 实为 media3 StuckPlayerDetector 发出的
                //   （“isPlaying 但 currentPosition 长时间不推进”），非 AudioSink 超时。
                //   见 .critical_steps.md 坑 48。当前主链路用方案 3（不卡），本恢复作为兵底。
                if (error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) {
                    recoverFromAudioSinkTimeout()
                }
            }
        })
    }

    /** 过久未恢复的连续错误计数，避免无限重建风暴。 */
    @Volatile
    private var audioSinkRecoveryCount = 0

    /**
     * 致命播放错误后的恢复：重置参数归零 + 重设 mediaItem + seek 回原位。
     *
     * 折中：先重置到 1.0x/0 半音（最稳配置）把声先恢复出来，
     * 同步把 UI 状态也回到 1.0x/0 —— 避免“UI 显示仍变调但实际已复位”的不一致。
     */
    private fun recoverFromAudioSinkTimeout() {
        if (audioSinkRecoveryCount >= MAX_AUDIO_SINK_RECOVERIES) {
            Log.w(TAG, "致命播放错误恢复已达上限($MAX_AUDIO_SINK_RECOVERIES)，放弃自动重建")
            _state.value = _state.value.copy(
                error = "音频输出反复出错，请重新导入音频或重启应用",
            )
            return
        }
        val p = mediaController ?: return
        val uri = originalUri ?: return
        audioSinkRecoveryCount++
        val resumeMs = p.currentPosition.coerceAtLeast(0L)
        Log.w(TAG, "★ 致命播放错误 → 重建 pipeline（第${audioSinkRecoveryCount}次），resume@${resumeMs}ms")

        // 1) 取消未下发的防抖任务 + 参数归零（用最稳配置重建 sink）
        paramDebounceJob?.cancel()
        pendingParams = null
        runCatching { p.playbackParameters = PlaybackParameters.DEFAULT }
        _state.value = _state.value.copy(speed = 1f, pitchSemitones = 0f)

        // 2) 重设同一 mediaItem（触发 decoder/audioSink 重建）+ prepare + seek + play
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(originalName)
                    .setArtist("NeuralStem")
                    .build(),
            )
            .build()
        p.stop()
        p.setMediaItem(item)
        p.prepare()
        p.seekTo(resumeMs)
        p.play()
    }

    init {
        Log.i(TAG, "VM init (instance=${hashCode()})")
        // ★ 预热歌单：至少 `load()` 一次 → 触发**迁移 + 建默认歌单 + 规范化**，
        //   即使从未打开“歌单页”（PlaylistViewModel 是懒建的，不预热就会漏规范化）。
        runCatching {
            val lists = playlistStore.load()
            // ★ 恢复“当前选中歌单”（持久化于 Settings）：保证永远有选中歌单。
            //   无效/缺失 → 回退默认歌单。
            val saved = settingsRepo.activeListId
            val active = lists.firstOrNull { it.id == saved }
                ?: lists.firstOrNull { it.isDefault } ?: lists.firstOrNull()
            if (active != null) {
                currentPlaylistId = active.id
                settingsRepo.activeListId = active.id
                _state.value = _state.value.copy(
                    playlistId = active.id,
                    playlistName = active.name,
                    playlistIsFolder = active.type == PlaylistStore.ListType.FOLDER,
                    trackCount = active.tracks.size,
                )
            }
        }
        // ★3.3：初始化循环按钮显示态（从持久化的播放模式）
        _state.value = _state.value.copy(repeatMode = settingsRepo.repeatMode)
        // V2(guide N4):连接 PlaybackService 的 session 拿 MediaController
        connectToService()
        // Task 2 / UI-V：把波形与封面流镜像到 UI state
        viewModelScope.launch {
            waveformExtractor.waveform.collect { wf ->
                Log.i(TAG, "waveform emit: points=${wf?.pointCount} dur=${wf?.durationMs}")
                _state.value = _state.value.copy(waveform = wf)
            }
        }
        viewModelScope.launch {
            coverExtractor.cover.collect { c ->
                _state.value = _state.value.copy(cover = c)
            }
        }
        // 进度轮询。放在 ViewModel scope,VM 销毁即停。
        // M3/P2(v2):session 的 player = Service 的 singlePlayer（多轨时它是主轨），
        // 因此 controller 的 currentPosition/duration 在两种模式下都有意义。
        viewModelScope.launch {
            // 上一次采样（用于“离群点”判定）
            var prevPos = -1L
            var prevReal = 0L
            var overDriftStreak = 0
            var progressTick = 0
            while (isActive) {
                val p = mediaController
                if (p != null) {
                    val dur = p.duration.coerceAtLeast(0L)
                    var pos = p.currentPosition
                    val isPlaying = p.isPlaying
                    // ★ seek 锁定：seek 刚下发后，ExoPlayer 的 currentPosition 可能短暂
                    //   回退到 seek 前的旧值（异步 seek + 缓冲）→ 进度“回跳 1-2s”。
                    val target = pendingSeekMs
                    if (target != null) {
                        if (pos >= target - SEEK_LOCK_TOLERANCE_MS) {
                            pendingSeekMs = null   // 已追上 → 解除锁定
                            lastShownMs = pos
                        } else {
                            pos = target            // 未追上 → 显示目标，防回跳
                            lastShownMs = pos
                        }
                    } else {
                        // 单调化：仅压制**小回退**（< MONOTONIC_TOLERANCE_MS 的抖动）；
                        // 大回退（重播到 0、意外跳转）必须**接受**。
                        val back = lastShownMs - pos
                        if (back > 0 && back < MONOTONIC_TOLERANCE_MS) {
                            pos = lastShownMs
                        } else {
                            lastShownMs = pos
                        }
                    }
                    _progress.value = Progress(pos, dur)
                    if (dur > 0 && _state.value.durationMs != dur) {
                        _state.value = _state.value.copy(durationMs = dur)
                    }

                    // ★★ UI 锚点（鲁棒版）：
                    //   实测 `currentPosition` 增量应 ~= 轮询间隔×speed，但**每~1s 会冒出
                    //   ±300~760ms 的离群点**（从 AudioTrack 播放头推算的抖动）。
                    //   若把这些离群点当“真实位置”去重锚 → UI 就跳。
                    //   策略：① 离群点**不参与重锚**；② 需**连续两次**漂移超阈才重锚（抑单点尖刺）。
                    val nowReal = SystemClock.elapsedRealtime()
                    val a = _anchor.value
                    val forceAnchor = a == null || !isPlaying || !a.isPlaying ||
                        a.speed != _state.value.speed || dur != a.durationMs
                    // 本采样是否“离群”（与上次采样对比）
                    val outlier = if (prevPos >= 0 && isPlaying) {
                        val dPos = pos - prevPos
                        val dReal = (nowReal - prevReal).coerceAtLeast(1L)
                        val expected = dReal * _state.value.speed   // 期望增量（ms）
                        dPos < 0 || dPos > expected * 2.5f + 50f
                    } else {
                        false
                    }
                    val drift = if (a != null) abs(a.extrapolate(nowReal) - pos) else 0L
                    if (forceAnchor) {
                        _anchor.value = UiClock(pos, nowReal, _state.value.speed, isPlaying, dur)
                        overDriftStreak = 0
                    } else if (!outlier && drift > ANCHOR_DRIFT_TOLERANCE_MS) {
                        overDriftStreak++
                        if (overDriftStreak >= 2) {   // 连续两次才重锚
                            _anchor.value = UiClock(pos, nowReal, _state.value.speed, isPlaying, dur)
                            overDriftStreak = 0
                        }
                    } else {
                        overDriftStreak = 0   // 离群或已对齐 → 清计数
                    }
                    prevPos = pos
                    prevReal = nowReal

                    // ★ 进度记忆：每 ~3s 把“当前歌单 + 当前曲 uri + 位置”落盘（低频）。
                    progressTick++
                    val pid = currentPlaylistId
                    if (pid != null && progressTick % 30 == 0) {
                        val uriStr = p.currentMediaItem?.localConfiguration?.uri?.toString()
                        runCatching { playlistStore.recordProgress(pid, uriStr, pos) }
                    }
                }
                // 100ms：采样更密 → 读秒数字更“跟手”。
                delay(PROGRESS_POLL_MS)
            }
        }
    }

    // ================= 歌单 / 切歌（队列化） =================
    //
    // 不变量：单轨播放的曲必须属于"当前歌单"；歌单顺序 = 播放顺序。
    // 队列用 media3 原生 `setMediaItems(items, startIndex)`（不再用 setMediaItem）。

    /** 把一首曲目转成 media3 [MediaItem]（带标题，供通知等）。 */
    private fun toMediaItem(t: PlaylistStore.TrackRef): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.parse(t.uri))
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(t.title).setArtist("NeuralStem").build(),
            )
            .build()

    /** 应用设置中的播放模式到 repeatMode（SEQUENTIAL→OFF / LOOP_ALL→ALL / LOOP_ONE→ONE）。 */
    private fun applyRepeatMode(p: Player) {
        p.repeatMode = when (settingsRepo.repeatMode) {
            SettingsRepository.RepeatMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
            SettingsRepository.RepeatMode.LOOP_ALL -> Player.REPEAT_MODE_ALL
            SettingsRepository.RepeatMode.LOOP_ONE -> Player.REPEAT_MODE_ONE
        }
    }

    /**
     * ★3.3：循环按钮 —— 依次切换播放模式（顺序 → 循环歌单 → 单曲循环 → 顺序），
     * 持久化并即时应用到播放器。
     */
    fun cycleRepeatMode() {
        val next = settingsRepo.repeatMode.next()
        setRepeatMode(next)
    }

    /** 设置播放模式（持久化 + 应用到 player + 更新 UI 态）。 */
    fun setRepeatMode(mode: SettingsRepository.RepeatMode) {
        settingsRepo.repeatMode = mode
        mediaController?.let { applyRepeatMode(it) }
        _state.value = _state.value.copy(repeatMode = mode)
        Log.i(TAG, "setRepeatMode: $mode")
    }

    /**
     * 以整个歌单为**播放队列**开始播放（从 [startIndex]）。
     * 歌单页点曲 / 导入后播放均走此入口。
     */
    fun playPlaylist(listId: String, startIndex: Int) {
        val all = playlistStore.load()
        // ★ 兜底：歌单不存在（或没选中）→ 一律回退**默认歌单**。
        val effective = all.firstOrNull { it.id == listId }
            ?: all.firstOrNull { it.isDefault }
            ?: all.firstOrNull()
        if (effective == null || effective.tracks.isEmpty()) {
            Log.w(TAG, "playPlaylist: empty/missing list $listId（且无可用默认歌单）")
            return
        }
        if (effective.id != listId) Log.w(TAG, "playPlaylist: list $listId 不存在 → 回退 ${effective.name}")
        val id = effective.id
        val p = mediaController
        if (p == null) {
            pendingLoad = { p2 -> startQueue(p2, id, startIndex) }
            return
        }
        startQueue(p, id, startIndex)
    }

    /**
     * 选中某歌单：**停当前 → 换队列 → 播该歌单的“上次播放曲”（无则第 1 首）**。
     * UI：点歌单卡片头 = 此动作。
     */
    fun selectPlaylist(listId: String) {
        val list = playlistStore.load().firstOrNull { it.id == listId }
        if (list == null) {
            Log.w(TAG, "selectPlaylist: missing list $listId")
            return
        }
        // ★ 已是当前歌单且已载入 → **不重建、不打断**（避免手抖重复点导致“重新播放”）。
        //   若已暂停则继续播，但**不重置进度**。
        if (listId == currentPlaylistId && _state.value.hasMedia) {
            Log.i(TAG, "selectPlaylist: $listId 已是当前歌单 → 不重建（仅确保在播）")
            mediaController?.play()
            return
        }
        if (list.tracks.isEmpty()) {
            // 空歌单：仅“选中”（后续导入进它）——清队列/停掉当前，但不报错
            Log.i(TAG, "selectPlaylist: $listId 为空 → 设当前歌单 + 停掉当前")
            currentPlaylistId = listId
            settingsRepo.activeListId = listId
            val p = mediaController
            p?.stop(); p?.clearMediaItems()
            lastShownMs = -1L; pendingSeekMs = null; _anchor.value = null
            _state.value = _state.value.copy(
                fileName = "", hasMedia = false, isPlaying = false, isReady = false,
                durationMs = 0L, waveform = null, cover = null,
                playlistId = listId, playlistName = list.name,
                playlistIsFolder = list.type == PlaylistStore.ListType.FOLDER,
                trackIndex = -1, trackCount = 0, separationOn = false, error = null,
            )
            return
        }
        // 从“上次播放曲”开始；找不到则第 1 首
        val idx = list.lastPlayedUri?.let { u -> list.tracks.indexOfFirst { it.uri == u } }
            ?.takeIf { it >= 0 } ?: 0
        Log.i(TAG, "selectPlaylist: $listId start=$idx (lastPlayed=${list.lastPlayedUri})")
        playPlaylist(listId, idx)
    }

    private fun startQueue(p: Player, listId: String, startIndex: Int) {
        val list = playlistStore.load().firstOrNull { it.id == listId } ?: return
        val items = list.tracks.map { toMediaItem(it) }
        val idx = startIndex.coerceIn(0, items.size - 1)
        // ★ 换队列前**必须退出多轨**：否则多轨的跟随轨 ExoPlayer 不会被停，
        //   新单曲在主轨播、旧 stems 在跟随轨继续播 → **两首歌同时出声**。
        //   （跟随轨由 Service 持有，只能经 CMD_EXIT 释放。）
        if (_state.value.isMultiTrack) {
            Log.i(TAG, "startQueue: 当前为多轨 → 先 exitMultiTrack（避免两首同时响）")
            exitMultiTrack()
        }
        currentPlaylistId = listId
        settingsRepo.activeListId = listId   // ★ 记住“当前选中歌单”（重启后恢复）
        applyRepeatMode(p)
        // ★ 切歌单/换队列：先**停掉当前**（用户要求“切换歌单时停当前”），再换队列。
        p.stop()
        p.setMediaItems(items, idx, 0L)
        p.prepare()
        p.play()
        // 记录原曲（供分离开关）
        originalUri = Uri.parse(list.tracks[idx].uri)
        originalName = list.tracks[idx].title
        isMultiTrackLoaded = false
        lastShownMs = -1L
        pendingSeekMs = null
        _state.value = _state.value.copy(
            fileName = list.tracks[idx].title,
            currentUri = list.tracks[idx].uri,
            playlistId = listId,
            playlistName = list.name,
            playlistIsFolder = list.type == PlaylistStore.ListType.FOLDER,
            trackIndex = idx,
            trackCount = items.size,
            separationOn = false,
            stemsAvailable = false,
            isReady = false,
            hasMedia = true,
            error = null,
        )
        Log.i(TAG, "startQueue: list=$listId name=${list.name} idx=$idx/${items.size}")
    }

    /**
     * F0.1 导入音频。
     *
     * 不再用单曲 [Player.setMediaItem]，改为「**加入当前歌单 → 以该歌单为队列播放**」：
     * - 当前为**文件歌单** → 插到“当前曲之后”；
     * - 当前为**文件夹歌单**（或无当前歌单）→ 切回**默认歌单**，插到当前曲之后。
     */
    fun loadAndPlay(uri: Uri, displayName: String) {
        // 幂等：同一 Uri 已在播则直接播（避免重建/重入从头重播）
        if (uri == originalUri && _state.value.hasMedia) {
            Log.i(TAG, "loadAndPlay: 已载入同一 Uri，跳过重载: $uri")
            mediaController?.play()
            return
        }
        Log.i(TAG, "loadAndPlay(→歌单): $displayName ($uri)")

        // 1) 决定目标歌单（当前文件夹歌单 → 切默认歌单）
        val lists = playlistStore.load()
        val defId = playlistStore.defaultListId
        val cur = currentPlaylistId?.let { id -> lists.firstOrNull { it.id == id } }
        val targetId = if (cur == null || cur.type == PlaylistStore.ListType.FOLDER) {
            defId
        } else {
            cur.id
        }
        // 2) 去重：同一 uri 已在目标歌单 → 不重复添加，直接定位到它。
        val uriStr = uri.toString()
        val target = lists.firstOrNull { it.id == targetId }
        val existingIdx = target?.tracks?.indexOfFirst { it.uri == uriStr } ?: -1
        if (existingIdx >= 0) {
            Log.i(TAG, "loadAndPlay: 已在歌单 $targetId 中，定位到 idx=$existingIdx")
            playPlaylist(targetId, existingIdx)
            return
        }
        // 3) 插到“当前曲之后”；若目标歌单非当前歌单（如刚切回默认），则插到末尾
        val insertIndex = if (targetId == currentPlaylistId) {
            (_state.value.trackIndex + 1).coerceAtLeast(0)
        } else {
            target?.tracks?.size ?: 0
        }
        val newList = target?.let { l ->
            l.copy(tracks = l.tracks.toMutableList().apply {
                add(insertIndex.coerceIn(0, size), PlaylistStore.TrackRef(
                    uriStr, displayName,
                ))
            })
        }
        if (newList != null) {
            playlistStore.save(lists.map { if (it.id == targetId) newList else it })
        }
        // 4) 以该歌单为队列，从新曲开始播放
        val startIdx = (target?.tracks?.size ?: 0).let { insertIndex.coerceIn(0, it) }
        playPlaylist(targetId, startIdx)
    }

    // ★ 注：已**移除单曲路径 `doLoad(single)`** —— 用户要求“不存在临时歌曲”，
    //   所有播放的曲都必须属于歌单（回原曲也走 `playPlaylist`）。

    // ---- U3：分离开关（原曲 ↔ 多轨）----

    private var originalUri: Uri? = null
    private var originalName: String = ""
    private var isMultiTrackLoaded = false

    /** 标记“已有可用分离结果”（由宿主传入分离产物后调用）。 */
    fun markStemsAvailable() {
        _state.value = _state.value.copy(stemsAvailable = true)
    }

    /**
     * U3：切换“分离开关”。
     * - on=true 且已有多轨 → 切到多轨；尚未载入 → 请宿主先调 [loadMultiTrack]。
     * - on=false → 回到原曲单轨（重新载入原曲）。
     *
     * @return true 表示本次调用已处理（切回原曲或切到多轨）；false 表示需要宿主先分离
     */
    fun toggleSeparation(on: Boolean): Boolean {
        if (on) {
            if (!isMultiTrackLoaded) return false   // 宿主需先 loadMultiTrack + markStemsAvailable
            mediaController?.play()
        } else {
            // 回原曲：退出多轨 → **回到当前歌单的当前曲**（不再走单曲路径；
            // 用户要求：不存在“临时歌曲”，播放的曲必须属于歌单）。
            exitMultiTrack()
            val listId = currentPlaylistId
            val targetUri = originalUri
            if (listId == null || targetUri == null) return true
            val list = playlistStore.load().firstOrNull { it.id == listId } ?: return true
            val idx = list.tracks.indexOfFirst { it.uri == targetUri.toString() }.takeIf { it >= 0 } ?: 0
            playPlaylist(listId, idx)
        }
        _state.value = _state.value.copy(separationOn = on)
        return true
    }

    fun togglePlayPause() {
        // M3/P2(v2):session 的 player = singlePlayer（多轨时它是主轨）；
        // Service 在其 play/pause 时同步跟随轨。
        val p = mediaController ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    /**
     * 显式播放（幂等）。用于“切多轨后确保在播”。
     * 不用 [togglePlayPause]：后者在原本已在播时会反而暂停
     * （典型：后台分离期间原曲在播 → 完成后切多轨 → toggle 就暂停了）。
     */
    fun play() {
        mediaController?.play()
    }

    /** UI-V：上一首 / 下一首（换碟）。仅在播放列表有多项时生效。 */
    fun next() {
        val p = mediaController ?: return
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
    }

    fun prev() {
        val p = mediaController ?: return
        if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
    }

    /**
     * 歌单被**清空**时调用（UI 在 `clearList` 后触发）。
     *
     * 与“删除”区别：**清空不取消选中** —— 歌单仍在（空），仍是“当前歌单”；
     * 只**停播 + 清队列 + 清曲名**，之后导入的曲仍进它。
     */
    fun onPlaylistCleared(listId: String) {
        if (listId != currentPlaylistId) return
        Log.i(TAG, "onPlaylistCleared: $listId → stop & clear queue（保留选中）")
        resetQueueButKeepSelection(listId)
    }

    /**
     * 歌单被**删除**时调用（UI 在 `deleteList` 后触发）。
     *
     * 若删的正是当前歌单 → **回退默认歌单**：
     * - 默认歌单**有曲** → **直接以它为队列播放**（从上次/首曲）；
     * - 默认歌单**为空** → 停播 + 归零，但**保留“选中默认歌单”**。
     *
     * 为何“有曲就接着播”：否则会出现“选中默认歌单、但 hasMedia=false”的不一致
     * （歌单页显示默认歌单有曲，播放器却“尚未导入音频”）。
     */
    fun onPlaylistRemoved(listId: String) {
        if (listId != currentPlaylistId) return
        Log.i(TAG, "onPlaylistRemoved: current playlist $listId gone → 回退默认歌单")
        val def = playlistStore.load().firstOrNull { it.isDefault }
        if (def != null && def.tracks.isNotEmpty()) {
            val idx = def.lastPlayedUri
                ?.let { u -> def.tracks.indexOfFirst { it.uri == u } }
                ?.takeIf { it >= 0 } ?: 0
            playPlaylist(def.id, idx)
        } else {
            // 默认歌单为空：停播 + 归零，但保留选中默认歌单
            resetQueueButKeepSelection(playlistStore.defaultListId)
        }
    }

    /**
     * 从歌单**移除一首曲**后调用（UI 在 `removeTrack` 后触发）。
     *
     * 为何：`removeTrack` 只改文件；若不同步 **Session 队列就会 ≠ 当前歌单**
     * （滑转盘能切到“已删的曲”）。当前曲被删时，按“链上下一”接播。
     * 非当前歌单的修改 → 忽略。
     */
    fun onTrackRemoved(listId: String, removedUri: String) {
        if (listId != currentPlaylistId) return
        val list = playlistStore.load().firstOrNull { it.id == listId } ?: return
        Log.i(TAG, "onTrackRemoved: $listId removed=$removedUri left=${list.tracks.size}")
        val p = mediaController
        val curUri = _state.value.currentUri
        val removingCurrent = (removedUri == curUri) || (removedUri == originalUri?.toString())

        // ★ 删的**不是当前曲** → 只从 Session 队列删该项（`removeMediaItem` **不打断**当前播放）。
        if (!removingCurrent && p != null) {
            val qi = (0 until p.mediaItemCount).indexOfFirst {
                p.getMediaItemAt(it).localConfiguration?.uri?.toString() == removedUri
            }
            if (qi >= 0) {
                p.removeMediaItem(qi)
                Log.i(TAG, "onTrackRemoved: 非当前曲 → removeMediaItem($qi)（不打断）")
                return
            }
        }
        // 删的是“当前曲”或队列里找不到 → 重建队列
        if (list.tracks.isEmpty()) {
            resetQueueButKeepSelection(listId)   // 删空了 → 同“清空”
            return
        }
        // 优先接播**未被删的当前曲**（若还在）；否则从头。
        val idx = curUri?.let { u -> list.tracks.indexOfFirst { it.uri == u } }
            ?.takeIf { it >= 0 } ?: 0
        playPlaylist(listId, idx)
    }

    /**
     * 歌单**排序**后调用（UI 在 `moveTrack` 后触发）。
     *
     * 若改的是当前歌单 → 同步 Session 队列（否则“队列顺序 ≠ 歌单顺序”）。
     * 用 `moveMediaItem`（**不打断当前播放**，媒体3 原生支持）。
     * 非当前歌单 → 忽略。
     */
    fun onTrackMoved(listId: String, from: Int, to: Int) {
        if (listId != currentPlaylistId) return
        val p = mediaController ?: return
        if (from !in 0 until p.mediaItemCount || to !in 0 until p.mediaItemCount) return
        Log.i(TAG, "onTrackMoved: $listId $from→$to")
        p.moveMediaItem(from, to)
    }

    /** 停播 + 清队列 + 状态归零；[keepListId] 非空时**保留该歌单为选中**。 */
    private fun resetQueueButKeepSelection(keepListId: String?) {
        val p = mediaController
        p?.stop()
        p?.clearMediaItems()
        currentPlaylistId = keepListId
        settingsRepo.activeListId = keepListId ?: playlistStore.defaultListId
        originalUri = null
        originalName = ""
        isMultiTrackLoaded = false
        lastShownMs = -1L
        pendingSeekMs = null
        _anchor.value = null
        _progress.value = Progress(0L, 0L)
        val kept = keepListId?.let { id -> playlistStore.load().firstOrNull { it.id == id } }
        _state.value = _state.value.copy(
            fileName = "",
            currentUri = null,
            hasMedia = false,
            isPlaying = false,
            isReady = false,
            durationMs = 0L,
            waveform = null,
            cover = null,
            playlistId = keepListId,
            playlistName = kept?.name ?: "",
            playlistIsFolder = kept?.type == PlaylistStore.ListType.FOLDER,
            trackIndex = -1,
            trackCount = 0,
            separationOn = false,
            error = null,
        )
    }

    /** F0.3 进度跳转;Task 4.4:多轨模式下全轨同步 seek(F4.4,由 router 广播) */
    fun seekTo(positionMs: Long) {
        val p = mediaController ?: return
        val clamped = positionMs.coerceIn(0L, p.duration.coerceAtLeast(0L))
        // ★ 先设锁定，再下发 seek：轮询期间用目标值兑底，防止 currentPosition 回退导致回跳。
        pendingSeekMs = clamped
        // ★ 立即重锚（否则 UI 会继续从旧锚点外推 → 短暂错位）。
        _anchor.value = UiClock(
            clamped, SystemClock.elapsedRealtime(),
            _state.value.speed, _state.value.isPlaying, p.duration.coerceAtLeast(0L),
        )
        p.seekTo(clamped)
        // R4：写入独立进度流（不触大 state → 不整页重组 → 不“闪”）
        _progress.value = _progress.value.copy(positionMs = clamped)
    }

    // ---------------- Task 4.4 / M3-P2:多轨播放（控制权在 PlaybackService） ----------------

    /**
     * 多轨模式已**搬进 [PlaybackService]**（M3/P2）。
     * VM 不再持 `MultiTrackController`，而是：
     * - 控制：经 `MediaController.sendCustomCommand(...)` 下发
     * - 状态：监听 `MediaController.Listener.onExtrasChanged` 取快照（事件驱动）
     */

    /** 把 Service 回传的多轨快照镜像到 UI state。 */
    private fun applyTracksSnapshot(json: String?) {
        val tracks = if (json.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    TrackUiState(
                        id = o.getString("id"),
                        label = o.getString("label"),
                        volume = o.optDouble("volume", 1.0).toFloat(),
                        muted = o.optBoolean("muted", false),
                        solo = o.optBoolean("solo", false),
                    )
                }
            }.getOrDefault(emptyList())
        }
        val inMt = tracks.isNotEmpty()
        // 多轨时 isReady 以 UI 意义上“轨已载入”为准；- 时长等待播放器 READY 后由 controller 监听补
        _state.value = _state.value.copy(
            isMultiTrack = inMt,
            tracks = tracks,
            isReady = _state.value.isReady || inMt,
        )
        Log.i(TAG, "tracks snapshot: ${tracks.size} 轨 (inMultiTrack=$inMt)")
    }

    /** 把 Service 回传的 EQ 状态快照镜像到 UI state（M3-P3）。 */
    private fun applyEqSnapshot(json: String?) {
        if (json.isNullOrBlank()) return
        runCatching {
            val o = JSONObject(json)
            val bands = o.optJSONArray("bands")
            val freqs = IntArray(bands?.length() ?: 0) { bands?.optInt(it, 0) ?: 0 }
            val levels = o.optJSONArray("levels")
            val lv = ShortArray(levels?.length() ?: 0) { (levels?.optInt(it, 0) ?: 0).toShort() }
            _state.value = _state.value.copy(
                eqAvailable = o.optBoolean("available", false),
                eqEnabled = o.optBoolean("enabled", false),
                eqFreqsHz = freqs,
                eqLevelsMb = lv,
            )
        }.onFailure { Log.w(TAG, "eq snapshot parse failed", it) }
    }

    /**
     * 发送多轨自定义命令。
     *
     * ⚠️ 与坑 40 同源：MediaController 异步连接，早期命令会扑空。
     * 连接未就绪时**暂存**，连上后按序重放（与 [pendingLoad] 同一思路）。
     */
    private fun sendMtCommand(name: String, args: Bundle = Bundle.EMPTY) {
        val mc = mediaController
        if (mc == null) {
            Log.i(TAG, "sendMtCommand queued (controller connecting): $name")
            pendingMtCommands += name to args
            return
        }
        mc.sendCustomCommand(MultiTrackSession.cmd(name), args)
    }

    /** 连接就绪后重放暂存的多轨命令。 */
    private fun flushMtCommands(c: MediaController) {
        if (pendingMtCommands.isEmpty()) return
        val queued = pendingMtCommands.toList()
        pendingMtCommands.clear()
        queued.forEach { (name, args) ->
            Log.i(TAG, "replay mt command: $name")
            c.sendCustomCommand(MultiTrackSession.cmd(name), args)
        }
    }

    /**
     * 载入分离结果的双轨/多轨，进入多轨模式（委托给 Service）。
     *
     * @param stems 每条轨的 (id, 显示名, 文件) —— 顺序即混音顺序
     */
    fun loadMultiTrack(stems: List<Triple<String, String, File>>) {
        if (stems.isEmpty()) return
        // 多轨状态经 MediaController.Listener.onExtrasChanged 回传（构建时已注册）
        // 进入多轨前暂停单轨（Service 内部也会暂停，双保险）
        mediaController?.pause()
        val items = JSONArray().apply {
            stems.forEach { (id, label, file) ->
                put(JSONObject().apply {
                    put("id", id); put("label", label); put("uri", Uri.fromFile(file).toString())
                })
            }
        }
        sendMtCommand(
            MultiTrackSession.CMD_LOAD,
            Bundle().apply {
                putString(MultiTrackSession.KEY_ITEMS, items.toString())
                // 通知标题 = 主轨曲目名（取自当前文件名）
                putString(MultiTrackSession.KEY_TITLE, _state.value.fileName)
            },
        )
        // 新载入的轨要继承当前变速变调（否则立刻时间轴漂移）
        val cur = _state.value
        mediaController?.playbackParameters =
            PlaybackParameters(cur.speed, semitonesToRate(cur.pitchSemitones))
        isMultiTrackLoaded = true
        _state.value = _state.value.copy(separationOn = true, stemsAvailable = true)
        Log.i(TAG, "loadMultiTrack → Service: ${stems.size} stems")
    }

    /** 退出多轨模式，回到单轨播放（委托给 Service）。 */
    fun exitMultiTrack() {
        sendMtCommand(MultiTrackSession.CMD_EXIT)
        isMultiTrackLoaded = false
        _state.value = _state.value.copy(
            isMultiTrack = false,
            tracks = emptyList(),
            separationOn = false,
            durationMs = mediaController?.duration?.coerceAtLeast(0L) ?: 0L,
        )
    }

    /** F4.2 设某轨音量 */
    fun setTrackVolume(trackId: String, volume: Float) {
        sendMtCommand(
            MultiTrackSession.CMD_SET_VOLUME,
            Bundle().apply {
                putString(MultiTrackSession.KEY_TRACK_ID, trackId)
                putFloat(MultiTrackSession.KEY_VOLUME, volume)
            },
        )
    }

    /** F4.3 静音切换 */
    fun toggleMute(trackId: String) {
        sendMtCommand(
            MultiTrackSession.CMD_TOGGLE_MUTE,
            Bundle().apply { putString(MultiTrackSession.KEY_TRACK_ID, trackId) },
        )
    }

    /** F4.3 独奏切换（再次点击取消） */
    fun toggleSolo(trackId: String) {
        sendMtCommand(
            MultiTrackSession.CMD_TOGGLE_SOLO,
            Bundle().apply { putString(MultiTrackSession.KEY_TRACK_ID, trackId) },
        )
    }

    // ---------------- Task 3:变速变调(guide F2.x) ----------------

    /**
     * F2.1/F2.3:设速度，播放中实时生效。只改 speed，音高保持(变速不变调)。
     *
     * ★R6：范围由设置项 X 决定，滑块区间 = [1/X, X]（见 [SettingsRepository.speedRangeX]）。
     * 这里再做一次夹紧（UI 已限制，这是防御层）。
     *
     * Task 4.4:多轨模式下改经 [MultiTrackController] 下发，**全轨联动**(F4.4)。
     */
    fun setSpeed(speed: Float) {
        val lo = settingsRepo.speedSliderMin
        val hi = settingsRepo.speedSliderMax
        val s = if (speed.isFinite()) speed.coerceIn(lo, hi) else 1f
        applyPlaybackParameters(speed = s)
        _state.value = _state.value.copy(speed = s)
        Log.i(TAG, "setSpeed: $s (range [$lo, $hi])")
    }

    /**
     * F2.2/F2.3:设音高,±12 半音,播放中实时生效。
     * 只改 pitch,速度保持(变调不变速)。
     *
     * Task 4.4:多轨模式下全轨联动(F4.4)。
     */
    fun setPitchSemitones(semitones: Float) {
        val st = semitones.coerceIn(MIN_PITCH_SEMITONES, MAX_PITCH_SEMITONES)
        applyPlaybackParameters(pitchSemitones = st)
        _state.value = _state.value.copy(pitchSemitones = st)
        Log.i(TAG, "setPitchSemitones: $st (rate=${semitonesToRate(st)})")
    }

    /**
     * 统一下发变速变调:单轨给 session player,多轨给 controller(全轨同步)。
     * 两个入参为 null 时沿用当前值。
     */
    fun applyPlaybackParameters(speed: Float? = null, pitchSemitones: Float? = null) {
        val cur = _state.value
        // ★ 防御：夹紧 + 排除 NaN/Inf（异常参数会令 Sonic/AudioTrack 崩或静音）
        val s0 = speed ?: cur.speed
        val st0 = pitchSemitones ?: cur.pitchSemitones
        val s = if (s0.isFinite()) s0.coerceIn(0.1f, 4.0f) else cur.speed
        val st = if (st0.isFinite()) st0.coerceIn(MIN_PITCH_SEMITONES, MAX_PITCH_SEMITONES) else cur.pitchSemitones
        val params = PlaybackParameters(s, semitonesToRate(st))
        // ★★ 防抖：变速/变调会触发 DefaultAudioSink 重配底层 AudioTrack
        //   （Sonic 改变输出采样率 → AudioTrack 需 setPlaybackParams/重建）。
        //   该重配有超时保护；短时间内多次重配（尤其低↔高来回跳）会撞上
        //   音频线程的过渡中间态 → AudioSink await 超时 → ERROR_CODE_TIMEOUT。
        //   现象：播放键点了进度不动、seek 无效（其实是 sink 挂了、STATE_IDLE）。
        //   故：只保留「最后一次」请求，等待 [PARAM_DEBOUNCE_MS] 静默期后再下发。
        pendingParams = params
        paramDebounceJob?.cancel()
        paramDebounceJob = viewModelScope.launch {
            delay(PARAM_DEBOUNCE_MS)
            val p = pendingParams ?: return@launch
            pendingParams = null
            // 下发前再校验：仅当与 player 当前值确有差异才下发（避免无谓重配）
            val ctrl = mediaController ?: return@launch
            if (ctrl.playbackParameters == p) return@launch
            Log.i(TAG, "applyPlaybackParameters（防抖后）: speed=${p.speed} pitch=${p.pitch}")
            ctrl.playbackParameters = p
        }
    }

    /** 一键回到 1.0x / 0 半音（与 set* 走同一下发路径，多轨时也同步）。 */
    fun resetSpeedPitch() {
        applyPlaybackParameters(speed = 1f, pitchSemitones = 0f)
        _state.value = _state.value.copy(speed = 1f, pitchSemitones = 0f)
        Log.i(TAG, "resetSpeedPitch")
    }

    // ---------------- M2-D / M3-P3:EQ(多段均衡，控制权在 Service) ----------------

    // M3-P3：EQ 已**搬进 PlaybackService**（多轨 player 在那里，能拿到会话）。
    // VM 不再持 EqController —— 控制经自定义命令下发，状态从快照读。

    /** EQ 开关（下发到 Service）。
     *  ★3.2：**首次开启**且当前曲线全零（用户未调过）时，先下发默认微笑曲线（低+3/高+3）。 */
    fun setEqEnabled(on: Boolean) {
        if (on && _state.value.eqLevelsMb.all { it == 0.toShort() }) {
            sendMtCommand(MultiTrackSession.CMD_APPLY_EQ_DEFAULT_CURVE)
            Log.i(TAG, "setEqEnabled(true): 曲线全零 → 先应用默认微笑曲线")
        }
        sendMtCommand(
            MultiTrackSession.CMD_SET_EQ_ENABLED,
            Bundle().apply { putBoolean(MultiTrackSession.KEY_EQ_ENABLED, on) },
        )
        // 乐观更新（快照回来后会被校正）
        _state.value = _state.value.copy(eqEnabled = on)
        Log.i(TAG, "setEqEnabled → Service: $on")
    }

    fun toggleEq() = setEqEnabled(!_state.value.eqEnabled)

    /** 设某段增益(mB)。 */
    fun setEqBandLevel(band: Int, levelMb: Short) {
        sendMtCommand(
            MultiTrackSession.CMD_SET_EQ_BAND,
            Bundle().apply {
                putInt(MultiTrackSession.KEY_EQ_BAND, band)
                putShort(MultiTrackSession.KEY_EQ_LEVEL_MB, levelMb)
            },
        )
    }

    /** 全部置零。 */
    fun resetEq() {
        sendMtCommand(MultiTrackSession.CMD_RESET_EQ)
    }

    /**
     * Spike/自动化专用：让 Service 应用测试曲线（低+6 / 中-6 / 高+6 dB）并开启。
     * 返回字符串仅用于日志（真实数据在 Service 日志与快照里）。
     */
    fun applyEqTestCurve(): String {
        sendMtCommand(MultiTrackSession.CMD_APPLY_EQ_TEST_CURVE)
        return "EQ test curve 已下发到 Service"
    }

    /**
     * ★3.2 诊断：全段极端值（true=全最高 / false=全最低）并开启，供 A/B 人耳验证。
     * 定案后可移除。
     */
    fun applyEqExtreme(toMax: Boolean) {
        sendMtCommand(
            MultiTrackSession.CMD_APPLY_EQ_EXTREME,
            Bundle().apply { putInt(MultiTrackSession.KEY_EQ_EXTREME, if (toMax) 1 else 0) },
        )
        _state.value = _state.value.copy(eqEnabled = true)
        Log.i(TAG, "applyEqExtreme toMax=$toMax")
    }

    /** EQ 摘要（本地已知部分；真实数据以 Service 日志为准）。 */
    fun eqSummary(): String =
        "enabled=${_state.value.eqEnabled} available=${_state.value.eqAvailable} (Service 侧)"

    /** M4/S1 诊断：让 Service 打印每条轨的 currentPosition（验证多轨 seek 后对齐）。 */
    fun debugPositions() {
        sendMtCommand(MultiTrackSession.CMD_DEBUG_POS)
    }

    /**
     * Activity onDestroy 调用。
     *
     * V2(N4):不断开与 [PlaybackService] 的连接,也不 release 播放器 ——
     * 播放器生命周期归 Service。仅断开本 VM 的 MediaController 引用,
     * 让 Activity 重建后再连(见 [connectToService])。
     */
    fun release() {
        mediaController?.release()
        mediaController = null
    }

    companion object {
        private const val TAG = "PlayerVm"

        /**
         * ★R6：速度范围已改为**由设置项 X 决定**（滑块区间 = [1/X, X]，默认 X=1.4）。
         * 这两个常量仅作 X 未就绪时的**防御性回退**，
         * 实际夹紧请用 [SettingsRepository.speedSliderMin] / [SettingsRepository.speedSliderMax]。
         */
        const val FALLBACK_MIN_SPEED = 0.5f
        const val FALLBACK_MAX_SPEED = 2.0f

        /** F2.2 音高范围(半音) */
        const val MIN_PITCH_SEMITONES = -12f
        const val MAX_PITCH_SEMITONES = 12f

        /** ★ 变速变调防抖窗口(ms)。
         *  AudioSink 重配 AudioTrack 约需数十 ms；留出静默期避免连续重配坨在
         *  音频线程的过渡态上 → 避免 ERROR_CODE_TIMEOUT。 */
        private const val PARAM_DEBOUNCE_MS = 150L

        /** ★ AudioSink 超时后最多自动重建次数（防一帧无限重建风暴）。 */
        private const val MAX_AUDIO_SINK_RECOVERIES = 3

        /** ★ seek 锁定容差（ms）：currentPosition 进入 [目标-容差, ∞) 即认为追上。 */
        private const val SEEK_LOCK_TOLERANCE_MS = 300L

        /** ★ 进度轮询间隔（ms）。100ms：采样更密，读秒数字更“跟手”。 */
        private const val PROGRESS_POLL_MS = 100L

        /** ★ 单调化容差（ms）：回退幅度小于此视为“抖动”而抑制；大于此视为重播/跳转而接受。 */
        private const val MONOTONIC_TOLERANCE_MS = 1_000L

        /** ★ UI 锚点漂移容差（ms）：外推与真实位置差超过此才重锚（否则交 UI 外推）。
         *  实测 `currentPosition` 会**停滞约 800ms 再补跳**（AudioTrack 播放头更新粒度）。
         *  阈值必须 **大于** 该停滞幅度，否则停滞被误判为“漂移”→ 周期性跳。
         *  1800ms ≈ 停滞上限 + 余量。（停滞期间声音仍在播，外推“领先”反而更接近真实） */
        private const val ANCHOR_DRIFT_TOLERANCE_MS = 1_800L

        /** 半音 → 线性倍率:Sonic 的 pitch 单位是倍率,2^(n/12)。伴 public 供单测 */
        fun semitonesToRate(semitones: Float): Float =
            Math.pow(2.0, semitones / 12.0).toFloat()

        /** 纯函数:毫秒 → m:ss,供 UI 与单测共用 */
        fun formatTime(ms: Long): String {
            val totalSec = ms / 1000
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }
}