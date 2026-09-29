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
import kotlin.math.abs
import pub.miku.neuralstem.settings.SettingsRepository

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
        /**
         * ★播放/暂停按钮的**显示意图**（`playWhenReady`）：用户“想播/想停”。
         * 为何不用 isPlaying：seek 时 ExoPlayer 会短暂 `isPlaying=false`（缓冲中），
         * 若按钮文字用 isPlaying → 松手时文字会“暂停↔播放”闪一下。playWhenReady 不受影响。
         */
        val playWhenReady: Boolean = false,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val isReady: Boolean = false,
        /**
         * ★UI 门控用：是否已**载入**媒体（doLoad 后即 true，**不随缓冲抖动**）。
         * 为何不用 isReady：isReady 是“真正可播”的瞬时状态；seek 时 ExoPlayer 会
         * `READY→BUFFERING→READY`，isReady 随之翻转 → 若用它门控 UI，整块
         * 波形/控件会被**移除重建 → 闪**。（与历史 R1++ 一致）
         */
        val hasMedia: Boolean = false,
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
        /** ★Step7:EQ 各段中心频率(Hz) */
        val eqFreqsHz: IntArray = IntArray(0),
        /** ★Step7:EQ 各段当前增益(mB) */
        val eqLevelsMb: ShortArray = ShortArray(0),
        /** ★Step3：播放模式（循环按钮显示）。 */
        val repeatMode: SettingsRepository.RepeatMode = SettingsRepository.RepeatMode.SEQUENTIAL,
        /** ★R3：内嵌封面（黑胶内圈）；null = 无。 */
        val cover: ImageBitmap? = null,
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
     * ★R4：**高频进度独立流**（避免每 100ms 整体换 PlayerState → 整页重组/闪）。
     * 只有进度条/转盘订阅它。
     */
    data class Progress(val positionMs: Long = 0L, val durationMs: Long = 0L)
    private val _progress = MutableStateFlow(Progress())
    val progressFlow: StateFlow<Progress> = _progress.asStateFlow()
    val progress: Progress get() = _progress.value

    /** ★R4：UI 时钟锚点（用本地单调时钟外推平滑时间轴，不随 10Hz 轮询跳动）。 */
    data class UiClock(
        val positionMs: Long,
        val realtimeMs: Long,
        val speed: Float,
        val isPlaying: Boolean,
        val durationMs: Long,
    ) {
        fun extrapolate(nowRealtimeMs: Long): Long {
            if (!isPlaying) return positionMs
            val dt = nowRealtimeMs - realtimeMs
            return positionMs + (dt * speed).toLong()
        }
    }
    private val _anchor = MutableStateFlow<UiClock?>(null)
    val anchorFlow: StateFlow<UiClock?> = _anchor.asStateFlow()

    /** ★R4：上次显示进度（单调化）；seek 锁定目标。 */
    @Volatile
    private var lastShownMs: Long = -1L
    @Volatile
    private var pendingSeekMs: Long? = null

    /** 波形提取器(Task 2);scope 用 VM 的,切歌自动取消上次 */
    private val waveformExtractor = WaveformExtractor(viewModelScope).apply {
        contextProvider = { getApplication<Application>() }
    }

    /** ★R3：封面提取器（黑胶内圈）。 */
    private val coverExtractor = CoverExtractor(viewModelScope).apply {
        contextProvider = { getApplication<Application>() }
    }

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
    private var pendingLoad: (Player) -> Unit = {}

    /** 连接前收到的多轨命令（坑 40：异步连接），连上后重放。 */
    private val pendingMtCommands = mutableListOf<Pair<String, Bundle>>()

    /** ★防抖：只保留最后一次待下发的变速变调参数（见 [applyPlaybackParameters]）。 */
    @Volatile
    private var pendingParams: PlaybackParameters? = null
    private var paramDebounceJob: Job? = null

    /**
     * ★状态机（骨架接入 · **I1**）：**换队列前先退多轨**的**单一判定点**。
     * - `mode` 由 Service 快照同步（见 [applyTracksSnapshot]）；
     * - 副作用 [SessionStateMachine.Effects.closeFollowers] **复用现有 [exitMultiTrack]**（下发 `CMD_EXIT`）。
     * - EQ 仍由 Service 管，故 attach/detach 暂为 no-op（I2 后续接入）。
     */
    private val session = SessionStateMachine(object : SessionStateMachine.Effects {
        override fun closeFollowers() = exitMultiTrack()
        override fun attachEq(sessionIds: List<Int>, enabled: Boolean) { /* EQ 由 Service 管 */ }
        override fun detachEq() { /* EQ 由 Service 管 */ }
    })

    /** 设置持久化（Step3：循环按钮的播放模式）。 */
    private val settingsRepo = SettingsRepository(getApplication())

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
            pendingLoad = {}
            replay(c)
            // M3/P2:重放连接前收到的多轨命令
            flushMtCommands(c)
        }, MoreExecutors.directExecutor())
    }

    /** 把所有 UI 监听挂到 session 的 player 上(波形、播放态、错误)。 */
    private fun attachPlayerListeners(p: Player) {
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // ★v2：session 的 player = singlePlayer，多轨时它**就是主轨**，其 isPlaying 即整组
                //   播放态（跟随轨由 Service 镜像）。故**单/多轨均由此驱动**——旧代码多轨时
                //   跳过更新，导致“多轨时播放/暂停按钮卡在旧态”。
                if (_state.value.isPlaying != isPlaying) {
                    _state.value = _state.value.copy(isPlaying = isPlaying)
                }
                // ★健康播放后重置 AudioSink 恢复额度，避免“早一次偶发超时”把上限吃光
                if (isPlaying) audioSinkRecoveryCount = 0
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // ★按钮“文字”来源：不受 seek 缓冲抖动影响（seek 时 isPlaying 会短暂 false）。
                if (!_state.value.isMultiTrack && _state.value.playWhenReady != playWhenReady) {
                    _state.value = _state.value.copy(playWhenReady = playWhenReady)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(
                    isReady = playbackState == Player.STATE_READY,
                    durationMs = p.duration.coerceAtLeast(0L),
                )
                // M2-D:AudioTrack 会话在准备好后才有效。注意 VM 持有的是
                // MediaController(不是真实 ExoPlayer) —— 会话 id 在 Service 里，
                // 故 EQ 挂载走 [PlaybackService.onPlayerReadyForEq] 钩子（见 attachEqHook）。
                if (playbackState == Player.STATE_READY && !_state.value.isMultiTrack) {
                    Log.i(TAG, "controller READY (session=${p.audioSessionId})")
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // ★R4：播放头**不连续**（seek / 重播到头）→ 重置单调化基准 + 锚点/进度。
                lastShownMs = newPosition.positionMs
                pendingSeekMs = null
                _anchor.value = null
                _progress.value = Progress(newPosition.positionMs, _progress.value.durationMs)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // ★R3：切歌清理：波形/封面重提取；进度/锚点重置（避免旧状态残留）。
                lastShownMs = -1L
                pendingSeekMs = null
                _progress.value = Progress(0L, _progress.value.durationMs)
                _anchor.value = null
                _state.value = _state.value.copy(waveform = null, cover = null)
                mediaItem?.localConfiguration?.uri?.let { uri ->
                    waveformExtractor.extractAsync(AudioSource.MediaUri(uri))
                    coverExtractor.extractAsync(uri)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // ★bug C 傅底 —— 致命错误（ERROR_CODE_TIMEOUT）自动重建 pipeline。
                //   ERROR_CODE_TIMEOUT 实为 media3 StuckPlayerDetector 发出（主链路已用方案3
                //   AudioTrack 原生，本恢复作为傅底：重设同一 mediaItem + seek 回原位）。
                Log.e(TAG, "播放失败: ${error.errorCodeName}", error)
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
            return
        }
        val p = mediaController ?: return
        // 9-24 未存 originalUri，直接从 player 取当前 mediaItem
        val item = p.currentMediaItem ?: return
        val resumeMs = p.currentPosition.coerceAtLeast(0L)
        audioSinkRecoveryCount++
        Log.w(TAG, "★ 致命播放错误 → 重建 pipeline（第${audioSinkRecoveryCount}次），resume@${resumeMs}ms")

        // 1) 取消未下发的防抖任务 + 参数归零（用最稳配置重建 sink）
        paramDebounceJob?.cancel()
        pendingParams = null
        runCatching { p.playbackParameters = PlaybackParameters.DEFAULT }
        _state.value = _state.value.copy(speed = 1f, pitchSemitones = 0f)

        // 2) 重设同一 mediaItem（触发 decoder/audioSink 重建）+ prepare + seek + play
        p.stop()
        p.setMediaItem(item)
        p.prepare()
        p.seekTo(resumeMs)
        p.play()
    }

    init {
        Log.i(TAG, "VM init (instance=${hashCode()})")
        // V2(guide N4):连接 PlaybackService 的 session 拿 MediaController
        connectToService()
        // Task 2 / R3：把波形与封面流镜像到 UI state
        // （★ 9-24 之前**未收集** → 波形/封面永不显示，即历史“坑 55”）。
        viewModelScope.launch {
            waveformExtractor.waveform.collect { wf ->
                _state.value = _state.value.copy(waveform = wf)
            }
        }
        viewModelScope.launch {
            coverExtractor.cover.collect { c ->
                _state.value = _state.value.copy(cover = c)
            }
        }
        // 进度轮询（★R4：写入独立 [progressFlow] + 锚点；不再整体换 state）。
        // M3/P2(v2):session 的 player = Service 的 singlePlayer（多轨时它是主轨），
        // 因此 controller 的 currentPosition/duration 在两种模式下都有意义。
        viewModelScope.launch {
            // 上一次采样（用于“离群点”判定）
            var prevPos = -1L
            var prevReal = 0L
            var overDriftStreak = 0
            while (isActive) {
                val p = mediaController
                if (p != null) {
                    val dur = p.duration.coerceAtLeast(0L)
                    var pos = p.currentPosition
                    val isPlaying = p.isPlaying
                    // ★ seek 锁定：seek 后 currentPosition 可能短暂回退到旧值 → 防回跳。
                    val target = pendingSeekMs
                    if (target != null) {
                        if (pos >= target - SEEK_LOCK_TOLERANCE_MS) {
                            pendingSeekMs = null
                            lastShownMs = pos
                        } else {
                            pos = target
                            lastShownMs = pos
                        }
                    } else {
                        // 单调化：仅压制小回退（抖动），大回退（重播/跳转）必接受。
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
                    // ★ 锚点（鲁棒）：离群点不重锚；需连续两次漂移超阈才重锚。
                    val nowReal = SystemClock.elapsedRealtime()
                    val a = _anchor.value
                    val forceAnchor = a == null || !isPlaying || !a.isPlaying ||
                        a.speed != _state.value.speed || dur != a.durationMs
                    val outlier = if (prevPos >= 0 && isPlaying) {
                        val dPos = pos - prevPos
                        val dReal = (nowReal - prevReal).coerceAtLeast(1L)
                        val expected = dReal * _state.value.speed
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
                        if (overDriftStreak >= 2) {
                            _anchor.value = UiClock(pos, nowReal, _state.value.speed, isPlaying, dur)
                            overDriftStreak = 0
                        }
                    } else {
                        overDriftStreak = 0
                    }
                    prevPos = pos
                    prevReal = nowReal
                }
                delay(PROGRESS_POLL_MS)
            }
        }
    }

    /** F0.1 导入音频:SAF Uri 直接喂给 session 的 player(Media3 内置 Handler 支持 content://) */
    fun loadAndPlay(uri: Uri, displayName: String) {
        Log.i(TAG, "loadAndPlay: $displayName ($uri)")
        val p = mediaController
        if (p == null) {
            // 连接未就绪:暂存,连接后重放(见 pendingLoad 注释)
            Log.i(TAG, "loadAndPlay queued (controller connecting): $displayName")
            pendingLoad = { p2 -> doLoad(p2, uri, displayName) }
            _state.value = _state.value.copy(fileName = displayName)
            return
        }
        doLoad(p, uri, displayName)
    }

    /** 真正的载入逻辑(连接就绪后调用)。 */
    private fun doLoad(p: Player, uri: Uri, displayName: String) {
        // ★I1（状态机不变量）：换曲前若处于多轨 → 先退多轨（关跟随轨）。
        //   否则跟随轨 ExoPlayer 不会被停 → 新曲（主轨）+ 旧 stems（跟随轨）**两首同时响**。
        session.beforeSetQueue()
        Log.i(TAG, "doLoad: $displayName ($uri)")
        // V2(N4):把曲目名放进 MediaMetadata,供系统媒体通知显示
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(displayName)
                    .setArtist("NeuralStem")
                    .build(),
            )
            .build()
        p.setMediaItem(item)   // 触发 onMediaItemTransition → 波形提取
        p.prepare()
        // ★Step3：应用当前播放模式（循环按钮）
        p.repeatMode = _state.value.repeatMode.toPlayerRepeatMode()
        p.play()
        _state.value = _state.value.copy(fileName = displayName, hasMedia = true, playWhenReady = true)
    }

    fun togglePlayPause() {
        // M3/P2(v2):session 的 player = singlePlayer（多轨时它是主轨）；
        // Service 在其 play/pause 时同步跟随轨。
        val p = mediaController ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    /** ★Step3：循环按钮 —— 依次切换播放模式（顺序 → 循环歌单 → 单曲循环 → 顺序）。 */
    fun cycleRepeatMode() {
        setRepeatMode(_state.value.repeatMode.next())
    }

    /** ★Step3：设置播放模式（持久化 + 应用到 player + 更新 UI）。 */
    fun setRepeatMode(mode: SettingsRepository.RepeatMode) {
        settingsRepo.repeatMode = mode
        mediaController?.repeatMode = mode.toPlayerRepeatMode()
        _state.value = _state.value.copy(repeatMode = mode)
        Log.i(TAG, "setRepeatMode: $mode")
    }

    /** F0.3 进度跳转;Task 4.4:多轨模式下全轨同步 seek(F4.4,由 router 广播) */
    fun seekTo(positionMs: Long) {
        val p = mediaController ?: return
        val clamped = positionMs.coerceIn(0L, p.duration.coerceAtLeast(0L))
        // ★R4：seek 后立刻用锚点外推（UI 立即到新位置），并锁定防“回跳 1-2s”。
        pendingSeekMs = clamped
        lastShownMs = clamped
        _anchor.value = UiClock(
            clamped, SystemClock.elapsedRealtime(),
            _state.value.speed, _state.value.isPlaying, p.duration.coerceAtLeast(0L),
        )
        _progress.value = _progress.value.copy(positionMs = clamped)
        p.seekTo(clamped)
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
        // ★状态机：把“真源”的多轨状态同步进来（不触发副作用）。
        session.syncMode(
            if (inMt) SessionMode.MultiTrack("primary", tracks.map { it.id })
            else SessionMode.Single,
        )
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
            val freqs = o.optJSONArray("bands")?.let { a -> IntArray(a.length()) { a.optInt(it) } } ?: IntArray(0)
            val lv = o.optJSONArray("levels")?.let { a -> ShortArray(a.length()) { a.optInt(it).toShort() } } ?: ShortArray(0)
            // ★I2：把 EQ 状态同步进状态机 → eqAvailable/eqEnabled 由状态机派生（单一真源）。
            session.syncEq(o.optBoolean("available", false), o.optBoolean("enabled", false))
            _state.value = _state.value.copy(
                eqAvailable = session.eqAvailable,
                eqEnabled = session.eqEnabled,
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
        Log.i(TAG, "loadMultiTrack → Service: ${stems.size} stems")
    }

    /** 退出多轨模式，回到单轨播放（委托给 Service）。 */
    fun exitMultiTrack() {
        sendMtCommand(MultiTrackSession.CMD_EXIT)
        _state.value = _state.value.copy(
            isMultiTrack = false,
            tracks = emptyList(),
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
     * F2.1/F2.3:设速度,0.5–2.0x,播放中实时生效。
     * 只改 speed,音高保持(变速不变调)。
     *
     * Task 4.4:多轨模式下改经 [MultiTrackController] 下发,**全轨联动**(F4.4)。
     */
    fun setSpeed(speed: Float) {
        // ★Step5：夹紧到设置项 X 派生的范围 [1/X, X]（UI 已限制，此为防御层）。
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
    private fun applyPlaybackParameters(speed: Float? = null, pitchSemitones: Float? = null) {
        val cur = _state.value
        val s = speed ?: cur.speed
        val st = pitchSemitones ?: cur.pitchSemitones
        val params = PlaybackParameters(s, semitonesToRate(st))
        // ★★防抖（bug C）：短时间多次改参会让 DefaultAudioSink 频繁重配底层 AudioTrack
        //   （Sonic 改变输出采样率 → 重建）→ 撞上过渡中间态 → StuckPlayerDetector 判卡死。
        //   现象：拖速度/音调滑块 → 毫秒内几十次改参 → 不动、没声、ERROR_CODE_TIMEOUT。
        //   故：只保留「最后一次」请求，等待静默期后再下发。
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

    /** 一键回到 1.0x / 0 半音 */
    fun resetSpeedPitch() {
        mediaController?.playbackParameters = PlaybackParameters.DEFAULT
        _state.value = _state.value.copy(speed = 1f, pitchSemitones = 0f)
        Log.i(TAG, "resetSpeedPitch")
    }

    // ---------------- M2-D / M3-P3:EQ(多段均衡，控制权在 Service) ----------------

    // M3-P3：EQ 已**搬进 PlaybackService**（多轨 player 在那里，能拿到会话）。
    // VM 不再持 EqController —— 控制经自定义命令下发，状态从快照读。

    /** EQ 开关（下发到 Service）。
     *  ★Step7：**首次开启**且当前曲线全零（用户未调过）时，先下发默认微笑曲线（低+3/高+3）。 */
    fun setEqEnabled(on: Boolean) {
        if (on && _state.value.eqLevelsMb.all { it == 0.toShort() }) {
            sendMtCommand(MultiTrackSession.CMD_APPLY_EQ_DEFAULT_CURVE)
            Log.i(TAG, "setEqEnabled(true): 曲线全零 → 先应用默认微笑曲线")
        }
        sendMtCommand(
            MultiTrackSession.CMD_SET_EQ_ENABLED,
            Bundle().apply { putBoolean(MultiTrackSession.KEY_EQ_ENABLED, on) },
        )
        // 乐观更新（快照回来后会被校正）；同时同步状态机。
        session.syncEq(_state.value.eqAvailable, on)
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

        /** ★防抖窗口（ms）：变速变调只保留最后一次下发（见 [applyPlaybackParameters]）。 */
        private const val PARAM_DEBOUNCE_MS = 150L

        /** ★R4：进度轮询间隔（ms）。 */
        private const val PROGRESS_POLL_MS = 100L

        /** ★R4：seek 锁定容忍（ms）。 */
        private const val SEEK_LOCK_TOLERANCE_MS = 300L

        /** ★R4：进度单调化容忍（ms）——仅压制小于此值的回退抖动，大回退必接受。 */
        private const val MONOTONIC_TOLERANCE_MS = 1_000L

        /** ★R4：锚点漂移阈值（ms）——超过则（连续两次后）重锚。 */
        private const val ANCHOR_DRIFT_TOLERANCE_MS = 1_800L

        /** ★bug C 傅底：致命播放错误自动重建 pipeline 的最大次数。 */
        private const val MAX_AUDIO_SINK_RECOVERIES = 3

        /** F2.1 速度范围 */
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f

        /** F2.2 音高范围(半音) */
        const val MIN_PITCH_SEMITONES = -12f
        const val MAX_PITCH_SEMITONES = 12f

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

/** ★Step3：设置层的 [SettingsRepository.RepeatMode] → media3 [Player.repeatMode]。 */
private fun SettingsRepository.RepeatMode.toPlayerRepeatMode(): Int = when (this) {
    SettingsRepository.RepeatMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
    SettingsRepository.RepeatMode.LOOP_ALL -> Player.REPEAT_MODE_ALL
    SettingsRepository.RepeatMode.LOOP_ONE -> Player.REPEAT_MODE_ONE
}