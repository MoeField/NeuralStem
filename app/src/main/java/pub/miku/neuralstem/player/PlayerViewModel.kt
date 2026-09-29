@file:OptIn(UnstableApi::class)

package pub.miku.neuralstem.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
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

    /** 波形提取器(Task 2);scope 用 VM 的,切歌自动取消上次 */
    private val waveformExtractor = WaveformExtractor(viewModelScope).apply {
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
                // 多轨模式下 isPlaying 由 MultiTrackController 的监听维护
                if (!_state.value.isMultiTrack) {
                    _state.value = _state.value.copy(isPlaying = isPlaying)
                }
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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Task 2:切歌时重新提取波形
                mediaItem?.localConfiguration?.uri?.let { uri ->
                    waveformExtractor.extractAsync(AudioSource.MediaUri(uri))
                }
            }
        })
    }

    init {
        Log.i(TAG, "VM init (instance=${hashCode()})")
        // V2(guide N4):连接 PlaybackService 的 session 拿 MediaController
        connectToService()
        // 进度轮询。放在 ViewModel scope,VM 销毁即停。
        // M3/P2(v2):session 的 player = Service 的 singlePlayer（多轨时它是主轨），
        // 因此 controller 的 currentPosition/duration 在两种模式下都有意义。
        viewModelScope.launch {
            while (isActive) {
                val p = mediaController
                if (p != null && p.isPlaying) {
                    _state.value = _state.value.copy(
                        positionMs = p.currentPosition,
                        durationMs = p.duration.coerceAtLeast(0L),
                    )
                }
                delay(100)
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
        p.seekTo(positionMs.coerceIn(0L, p.duration.coerceAtLeast(0L)))
        _state.value = _state.value.copy(positionMs = positionMs)
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
        // M3/P2(v2):变速变调经 controller 下发；多轨时 Service 负责同步到跟随轨
        mediaController?.playbackParameters = params
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