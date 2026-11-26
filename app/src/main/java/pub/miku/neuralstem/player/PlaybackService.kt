package pub.miku.neuralstem.player

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 后台播放服务（V2/N4，M3/P2 v2 架构）。
 *
 * ## v2 架构（为何不用 v1 的 ForwardingPlayer）
 * v1 用 `ForwardingPlayer` 伪装 session 的 player，实测**多轨时不出通知/不进前台服务**
 * （根因在 media3 框架对“伪装 player + 后设 media items”的判定盲区，试 5 种补丁无效）。
 *
 * ⇒ v2：**session 永远只认真实的 `singlePlayer`**；多轨的**主轨就是它**，
 *   其余 stems 为“哑跟随”播放器（不进 session）。于是多轨与单轨对 media3 而言
 *   无区别 → **通知/前台服务天然工作**。
 *
 * ```
 * MediaSession(singlePlayer)   ← 永远
 *   单轨：singlePlayer 播原始音频
 *   多轨：singlePlayer 播主轨（第一条 stem）；followerPlayers 播其余
 * ```
 * 详见 tech_design_multitrack_service.artifact.md §0b。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /** 单轨/多轨主轨播放器 —— **也是 session 的 player**（真实 ExoPlayer）。 */
    private var singlePlayer: ExoPlayer? = null

    /** 主线程 handler（用于快照推送去抖）。 */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * ★2026-11-06：多轨变速变调参数缓存。
     *
     * 单轨由 ExoPlayer 自己记；多轨要把同一组参数交给 `StemMixer`。
     * 进入多轨时用它初始化（否则会从 1.0× 开始，与 UI 显示不一致）。
     */
    @Volatile private var lastSpeed: Float = 1f
    @Volatile private var lastPitchRate: Float = 1f

    /**
     * v0.3.1 Step 3a：多轨会话状态宿主。
     *
     * 3a 只做字段搬家；load/exit/音量/焦点方法仍在 Service，通过下方 alias 访问，
     * 调用点零改动。3c 删 alias 后 Service 只通过 coordinator 访问。
     */
    private val coordinator: MultiTrackCoordinator = MultiTrackCoordinator(
        context = this,
        onAudioSessionChanged = { sessionId ->
            // AudioTrack 重建后 sessionId 变了；在主线程重挂多轨 EQ。
            handler.post {
                if (coordinator.active) {
                    Log.i(TAG, "mixer audio session changed → $sessionId, reattach EQ")
                    coordinator.current?.let { attachMultiTrackEq(it) }
                }
            }
        },
        getOriginalMeta = {
            val item = singlePlayer?.currentMediaItem
            item?.localConfiguration?.uri?.toString() to item?.mediaMetadata?.title?.toString()
        },
        getSpeedPitch = { lastSpeed to lastPitchRate },
        onLoaded = { c, startMs, logMsg ->
            singlePlayer?.volume = 0f
            coordinator.active = true
            c.playWhenAllReady(startMs)
            singlePlayer?.play()
            enterMultiTrackFocus()
            attachMultiTrackEq(c)
            snapshotPublisher.startProgress()
            pushTracksSnapshot()
            Log.i(TAG, logMsg)
        },
        onAborted = {
            if (coordinator.active) {
                coordinator.active = false
                exitMultiTrackFocus()
                snapshotPublisher.stopProgress()
            }
            singlePlayer?.volume = 1f
            pushTracksSnapshot()
        },
        onExitCleanup = {
            exitMultiTrackFocus()
            snapshotPublisher.stopProgress()
            cancelEqRetry()
            // ★先暂停门面再恢复音量：exit 后才 doLoad 原曲；若先 volume=1，
            //   门面还在播旧主轨 stem，会与关闭中的 mixer 短暂叠音。
            runCatching { singlePlayer?.pause() }
            singlePlayer?.volume = 1f
            // M3-P3/T2：退出后回单轨重新挂 EQ（attachSingleEq 自身会判 sessionId != 0）。
            eqHost.detachAll()
            singlePlayer?.let { attachSingleEq(it) }
            pushTracksSnapshot()
        },
        onFocusChange = { onMultiTrackFocusChange(it) },
    )


    /**
     * M3-P3：EQ 控制器（**从 VM 搬进 Service**）。
     * 单轨挂 singlePlayer 会话；多轨每轨各挂一份（参数由 EqController 同步）。
     */
    private val eqHost: EqHost = EqHost(
        handler = handler,
        requestSnapshot = ::requestSnapshotPush,
        isInMultiTrack = { coordinator.active },
        currentMultiTrack = { coordinator.current },
    )

    private val snapshotPublisher: SnapshotPublisher = SnapshotPublisher(
        handler = handler,
        session = { mediaSession },
        eqJson = { eqHost.snapshotJson() },
        onTick = { reconcileFacadeToMixer() },
        snapshot = {
            val c = coordinator.current
            if (!coordinator.active || c == null) {
                null
            } else {
                SnapshotPublisher.SnapshotData(
                    tracksJson = SnapshotPublisher.buildTracksJson(c.trackList, c.soloTrackId),
                    originalUri = coordinator.originalUri,
                    originalTitle = coordinator.originalTitle,
                    speed = lastSpeed,
                    pitchRate = lastPitchRate,
                    mixPosMs = c.currentPositionMs(),
                    mixDurMs = c.durationMs(),
                    mixPlaying = c.isPlaying(),
                    mixEnded = c.isEnded(),
                )
            }
        },
    )

    override fun onCreate() {
        super.onCreate()
        // ★R2/bug C：统一构建器 → 开启 AudioTrack 原生变速变调（绕过 Sonic，避免频繁改参卡死）。
        // ★G-3：装“按 scheme 分发”的 DataSource → 同一个 player 既能播文件也能播分离流（stem://）。
        val player = buildAudioExoPlayer(
            this,
            handleAudioFocus = true,
            mediaSourceFactory = DefaultMediaSourceFactory(
                StemStreamFactory(DefaultDataSource.Factory(this)),
            ),
        )
        singlePlayer = player

        // 主轨播放/暂停/seek → 同步给跟随轨（多轨模式下）
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    Log.i(TAG, "player READY, audioSessionId=${player.audioSessionId}")
                    // M3-P3：单轨(非多轨)时，把 EQ 挂到主播放器会话
                    if (!coordinator.active) attachSingleEq(player)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // ★★ 2026-11-01 坑：**不能**用主轨的 isPlaying 去驱动 mixer。
                //   主轨（singlePlayer）现在被静音当“门面”，它自己会因为
                //   AudioTrack 暂停/缓冲而反复 isPlaying=false，
                //   直接映射会让 mixer 刚启动就被 pause（真机实测 `pause @2048frame`）。
                //   **方向反过来：mixer 才是真实的播放状态源。**
                if (coordinator.active) {
                    Log.d(TAG, "onIsPlayingChanged($isPlaying)：多轨下忽略（真源是 StemMixer）")
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                // 单轨的真源仍是 ExoPlayer；缓存下来供进入多轨时初始化 StemMixer。
                lastSpeed = playbackParameters.speed
                lastPitchRate = playbackParameters.pitch
                if (coordinator.active) {
                    // ★多轨不再写门面参数（VM 走 CMD_SET_SPEED_PITCH）。
                    //   若仍然收到变更，只记录：混音器的真源是它自己的 targetSpeed/targetPitchRate。
                    Log.i(TAG, "多轨下收到门面 playbackParameters 变更（忽略）：$playbackParameters")
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                Log.i(TAG, "onPositionDiscontinuity: reason=$reason → ${newPosition.positionMs}ms (inMt=${coordinator.active})")
                // ★2026-11-01：多轨下 seek 只需告知混音器（它只有一个 framePos，
                //   所有 stem 自然同步）；不再需要逐个 seek 跟随轨。
                if (coordinator.active && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    coordinator.seekTo(newPosition.positionMs)
                }
            }
        })

        // v2：session 直接认 singlePlayer（真实 player）—— 通知/前台服务天然工作
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
        Log.i(TAG, "service created, session ready (v2: session=singlePlayer)")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    // ---------------- 多轨：状态快照回传 ----------------

    private fun ensureMultiTrack(): MultiTrackController = coordinator.ensure()

    /**
     * 请求推送快照。
     *
     * ★ 去抖：多轨时每轨的 `onPlaybackStateChanged` 都会触发，一次 load 会
     *   连推十几次（实测）。用主线程 handler 合并成**一帧内一次**。
     */
    private fun requestSnapshotPush() = snapshotPublisher.request()

    private fun pushTracksSnapshot() = snapshotPublisher.pushNow()

    // ---------------- M3-P3：EQ（委托 EqHost） ----------------

    /** 单轨 EQ：挂到主播放器（singlePlayer）的会话。 */
    private fun attachSingleEq(p: Player) = eqHost.attachSingle(p.audioSessionId)

    /** 多轨 EQ：只挂一份在混音输出上（详见 EqHost）。 */
    private fun attachMultiTrackEq(c: MultiTrackController) = eqHost.attachMultiTrack(c)

    private fun cancelEqRetry() = eqHost.cancelRetry()

    /** EQ 状态 JSON（供 UI 渲染面板）。 */
    private fun eqSnapshotJson(): String = eqHost.snapshotJson()

    private fun handleSetEqEnabled(args: Bundle): SessionResult {
        eqHost.setEnabled(args.getBoolean(MultiTrackSession.KEY_EQ_ENABLED, false))
        return ok()
    }

    private fun handleSetEqBand(args: Bundle): SessionResult {
        val band = args.getInt(MultiTrackSession.KEY_EQ_BAND, -1)
        val level = args.getShort(MultiTrackSession.KEY_EQ_LEVEL_MB, 0)
        if (!eqHost.setBand(band, level)) return err("band 越界或 EQ 不可用")
        return ok()
    }

    private fun handleResetEq(): SessionResult {
        eqHost.reset()
        return ok()
    }

    /**
     * M4/S1 诊断：打印每条轨的 `currentPosition`。
     *
     * `ExoPlayer.currentPosition` 基于 **AudioTrack 播放头**，是**每轨真实的渲染位置**
     * （与 `dumpsys` 的 output-thread 帧计数不同，后者对同 thread 的所有轨是同一个值，
     * 无法区分单轨）。因此用它验证“seek 后多轨是否对齐”。
     */
    private fun handleDebugPos(): SessionResult {
        val main = singlePlayer
        Log.i(
            TAG,
            "POS_MAIN pos=${main?.currentPosition} state=${main?.playbackState} " +
                "playing=${main?.isPlaying} pp=${main?.playbackParameters}",
        )
        coordinator.current?.trackList?.forEachIndexed { i, t ->
            Log.i(
                TAG,
                "POS_TRACK[$i] id=${t.id} label=${t.label} uri=${t.uri}",
            )
        }
        // ★2026-11-01：只有一个 framePos，所有 stem 必然同帧 —— 报混音器位置即可
        coordinator.current?.let {
            Log.i(
                TAG,
                "POS_MIX pos=${it.currentPositionMs()} dur=${it.durationMs()} " +
                    "playing=${it.isPlaying()} tracks=${it.trackList.size}",
            )
        }
        return ok()
    }

    /** 诊断/自动化：低+6 / 中-6 / 高+6 dB 测试曲线，并开启。 */
    private fun handleApplyEqTestCurve(): SessionResult {
        if (!eqHost.applyTestCurve()) return err("EQ unavailable (bandCount=0)")
        return ok()
    }

    /** ★Step7：应用默认微笑曲线（低+3/高+3）。 */
    private fun handleApplyEqDefaultCurve(): SessionResult {
        if (!eqHost.applyDefaultCurve()) return err("EQ unavailable (bandCount=0)")
        return ok()
    }

    // ---------------- ★A3：多轨音频焦点 / 门面同步 ----------------

    /**
     * 进入多轨：门面不再自己抢焦点，改由 Service 持有。
     * 否则焦点丢失时只有静音门面被 ExoPlayer 暂停，StemMixer 继续满音量。
     */
    private fun enterMultiTrackFocus() {
        val sp = singlePlayer ?: return
        if (coordinator.audioAttributes == null) coordinator.audioAttributes = sp.audioAttributes
        // false = 不再让 ExoPlayer 管理焦点；由 coordinator.focusListener 统一处理。
        coordinator.audioAttributes?.let { sp.setAudioAttributes(it, false) }
        if (coordinator.focusHeld) return
        val am = getSystemService(AudioManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val granted = am.requestAudioFocus(
            coordinator.focusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        coordinator.focusHeld = granted
        Log.i(TAG, "multi-track audio focus held=$granted")
    }

    /** 退出多轨：放弃 Service 的焦点，恢复门面自己的焦点处理。 */
    private fun exitMultiTrackFocus() {
        val am = getSystemService(AudioManager::class.java)
        if (coordinator.focusHeld) {
            @Suppress("DEPRECATION")
            am?.abandonAudioFocus(coordinator.focusListener)
        }
        coordinator.focusHeld = false
        coordinator.focusWasPlaying = false
        coordinator.audioAttributes?.let { singlePlayer?.setAudioAttributes(it, true) }
    }

    private fun onMultiTrackFocusChange(change: Int) {
        if (!coordinator.active) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                coordinator.focusWasPlaying = coordinator.current?.isPlaying() == true
                coordinator.current?.pause()
                singlePlayer?.pause()
                coordinator.focusHeld = false
                Log.i(TAG, "multi-track focus lost($change) → paused (wasPlaying=${coordinator.focusWasPlaying})")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val resume = coordinator.focusWasPlaying
                coordinator.focusWasPlaying = false
                coordinator.focusHeld = true
                if (resume) {
                    coordinator.current?.play()
                    singlePlayer?.play()
                }
                Log.i(TAG, "multi-track focus gain → resume=$resume")
            }
        }
    }

    /**
     * 让静音门面的播放态跟真源 mixer 一致（通知栏/UI 才不会反着显示）。
     *
     * 媒体键的 onPlayerCommandRequest 只能拿到 COMMAND_PLAY_PAUSE、拿不到方向；
     * 我们先按 mixer 状态切换，再让默认命令执行，150ms 后按 mixer 纠正门面。
     */
    private fun reconcileFacadeToMixer() {
        if (!coordinator.active) return
        val c = coordinator.current ?: return
        val sp = singlePlayer ?: return
        if (!c.isPlaying()) {
            if (sp.isPlaying) {
                sp.pause()
                Log.i(TAG, "reconcile: facade → pause (mixer paused)")
            }
            return
        }
        // ★P2-3：mixer 在播时，门面必须处于可推进的播放态。
        //   门面播的是主轨 stem，可能比 mixer 先 EOF（STATE_ENDED），
        //   此时只 play() 不会推进时钟 → 拉回起点重新当门面。
        if (sp.playbackState == Player.STATE_ENDED) {
            runCatching { sp.seekTo(0) }
            sp.play()
            Log.i(TAG, "reconcile: facade ENDED → restart as clock (mixer playing)")
        } else if (!sp.isPlaying) {
            sp.play()
            Log.i(TAG, "reconcile: facade → play (mixer playing)")
        }
    }

    // ---------------- 多轨：命令处理 ----------------

    /** 多轨 load 失败：确保不会留下“门面静音 + 无 mixer”的无声状态。 */
    private fun abortMultiLoad(c: MultiTrackController): SessionResult {
        Log.w(TAG, "multi load failed → restore single-track facade")
        coordinator.abort(c)
        return err("多轨加载失败")
    }

    private fun handleLoadMultiTrack(args: Bundle): SessionResult {
        val json = args.getString(MultiTrackSession.KEY_ITEMS) ?: return err("items 缺失")
        val items = parseItems(json) ?: return err("items 解析失败")
        if (items.isEmpty()) return err("items 为空")
        val startMs = args.getLong(MultiTrackSession.KEY_START_MS, 0L)
        val title = args.getString(MultiTrackSession.KEY_TITLE)
        coordinator.loadDisk(items, startMs, title)?.let { return err(it) }
        return ok("loading")
    }

    /**
     * ★G-3：载入流式多轨（边分离边播）。
     * items JSON 每项 `{id,label,host}`；uri 拼为 `stem://<host>`，
     * 主轨（singlePlayer）已装 [StemStreamFactory] → 自动从 ring buffer 读。
     */
    private fun handleLoadStream(args: Bundle): SessionResult {
        val json = args.getString(MultiTrackSession.KEY_ITEMS) ?: return err("items 缺失")
        val arr = try {
            JSONArray(json)
        } catch (t: Throwable) { return err("items 解析失败") }
        if (arr.length() == 0) return err("items 为空")
        val items = ArrayList<MultiTrackController.StreamItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { "track$i" }
            val label = o.optString("label").ifBlank { id }
            val host = o.optString("host").ifBlank { if (id.contains("accomp")) "accompaniment" else "vocals" }
            items += MultiTrackController.StreamItem(
                id = id,
                label = label,
                uri = Uri.parse("${StemPcmDataSource.SCHEME}://$host"),
            )
        }
        if (items.isEmpty()) return err("items 为空")
        val startMs = args.getLong(MultiTrackSession.KEY_START_MS, 0L)
        val title = args.getString(MultiTrackSession.KEY_TITLE)
        coordinator.loadStream(items, startMs, title)?.let { return err(it) }
        return ok("loading-stream")
    }

    private fun handleSetVolume(args: Bundle): SessionResult {
        val id = args.getString(MultiTrackSession.KEY_TRACK_ID) ?: return err("trackId 缺失")
        if (!coordinator.setVolume(id, args.getFloat(MultiTrackSession.KEY_VOLUME, 1f))) {
            return err("非多轨")
        }
        pushTracksSnapshot()
        return ok()
    }

    private fun handleToggleMute(args: Bundle): SessionResult {
        val id = args.getString(MultiTrackSession.KEY_TRACK_ID) ?: return err("trackId 缺失")
        if (!coordinator.toggleMute(id)) return err("非多轨")
        pushTracksSnapshot()
        return ok()
    }

    private fun handleToggleSolo(args: Bundle): SessionResult {
        val id = args.getString(MultiTrackSession.KEY_TRACK_ID) ?: return err("trackId 缺失")
        if (!coordinator.toggleSolo(id)) return err("非多轨")
        pushTracksSnapshot()
        return ok()
    }

    private fun handleExit(): SessionResult {
        Log.i(
            TAG,
            "EXIT_MT before: active=${coordinator.active} mainItem=${singlePlayer?.currentMediaItem?.localConfiguration?.uri} " +
                "followers=${coordinator.current?.trackList.orEmpty().joinToString { "${it.id}:${it.uri}" }}",
        )
        coordinator.exit()
        Log.i(
            TAG,
            "EXIT_MT after: active=${coordinator.active} mainItem=${singlePlayer?.currentMediaItem?.localConfiguration?.uri}",
        )
        return ok()
    }

    private fun parseItems(json: String): List<Triple<String, String, Uri>>? = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Triple(o.getString("id"), o.getString("label"), Uri.parse(o.getString("uri")))
        }
    }.getOrNull()

    private fun ok(msg: String = "ok") =
        SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putString("msg", msg) })

    private fun err(msg: String) =
        SessionResult(SessionError.ERROR_BAD_VALUE, Bundle().apply { putString("error", msg) })

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val builder = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            // ★B：自定义命令只暴露给本应用；外部 controller（蓝牙/其他 App）看不到。
            if (controller.uid == Process.myUid()) {
                MultiTrackSession.ALL_COMMANDS.forEach { builder.add(MultiTrackSession.cmd(it)) }
            } else {
                Log.w(TAG, "onConnect: external controller=${controller.packageName}, no custom commands")
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(builder.build())
                .build()
        }

        /**
         * ★2026-11-10（A3）：系统媒体键/通知栏/锁屏的标准 play/pause 走这里。
         *
         * 多轨时 session 仍认静音门面 `singlePlayer`：media3 会先把命令交到这里
         * （返回 SUCCESS 后才执行默认动作），我们借此同步驱动真源 `StemMixer`，
         * 再让默认路径更新门面/通知状态。
         *
         * 不能靠 `onIsPlayingChanged` 反向驱动：门面是静音轨道，缓冲/焦点会让它
         * 自己 isPlaying=false，直接映射会把刚启动的 mixer 误暂停（历史坑）。
         *
         * 方向判定：回调发生在默认命令执行**之前**，`playWhenReady` 仍是旧值：
         * 请求 pause 时旧值 true；请求 play 时旧值 false。
         */
        @Suppress("DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            if (coordinator.active) {
                when (playerCommand) {
                    Player.COMMAND_PLAY_PAUSE -> {
                        // 回调发生在默认命令执行之前，且这里拿不到“play 还是 pause”。
                        // 先让默认命令更新门面的 playWhenReady，下一帧再按**目标意图**
                        // 同步 mixer；不能按 mixer 当前状态 toggle（分叉时会反向）。
                        handler.post {
                            if (!coordinator.active) return@post
                            val c = coordinator.current ?: return@post
                            val wantPlay = singlePlayer?.playWhenReady == true
                            if (wantPlay) {
                                c.play()
                                Log.i(TAG, "media PLAY_PAUSE → play (mirror facade intent)")
                            } else {
                                c.pause()
                                coordinator.focusWasPlaying = false
                                Log.i(TAG, "media PLAY_PAUSE → pause (mirror facade intent)")
                            }
                        }
                    }
                    Player.COMMAND_SET_SPEED_AND_PITCH -> {
                        // 回调时门面参数还是旧值；等默认命令应用后再读，同步给 mixer。
                        handler.post {
                            if (!coordinator.active) return@post
                            val p = singlePlayer?.playbackParameters ?: return@post
                            lastSpeed = p.speed
                            lastPitchRate = p.pitch
                            coordinator.current?.setSpeedPitch(p.speed, p.pitch)
                            Log.i(TAG, "media SET_SPEED_AND_PITCH → ${p.speed}x/${p.pitch}")
                        }
                    }
                }
            }
            return SessionResult.RESULT_SUCCESS
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            // ★B：第二道门。即使外部 controller 猜到了 action，也拒绝执行。
            if (controller.uid != Process.myUid()) {
                Log.w(TAG, "reject custom command from ${controller.packageName}/${controller.uid}")
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            Log.i(TAG, "onCustomCommand: ${customCommand.customAction}")
            val result = try {
                when (customCommand.customAction) {
                MultiTrackSession.CMD_LOAD -> handleLoadMultiTrack(args)
                MultiTrackSession.CMD_LOAD_STREAM -> handleLoadStream(args)
                // P1-B：load 在 coordinator.io 进行中，这三条命令会读/写半初始化 tracks；
                // 与 PLAY/PAUSE 对齐，完成前只接受不作用。
                MultiTrackSession.CMD_SET_VOLUME ->
                    if (coordinator.loadPending) ok("loading") else handleSetVolume(args)
                MultiTrackSession.CMD_TOGGLE_MUTE ->
                    if (coordinator.loadPending) ok("loading") else handleToggleMute(args)
                MultiTrackSession.CMD_TOGGLE_SOLO ->
                    if (coordinator.loadPending) ok("loading") else handleToggleSolo(args)
                MultiTrackSession.CMD_EXIT -> handleExit()
                MultiTrackSession.CMD_PLAY -> {
                    // 多轨 load 在 IO 中，完成前命令只接受、不触碰半初始化 mixer。
                    if (coordinator.loadPending) ok("loading") else {
                        coordinator.current?.play()
                        singlePlayer?.play()
                        ok()
                    }
                }
                MultiTrackSession.CMD_PAUSE -> {
                    if (coordinator.loadPending) ok("loading") else {
                        coordinator.current?.pause()
                        singlePlayer?.pause()
                        coordinator.focusWasPlaying = false
                        ok()
                    }
                }
                MultiTrackSession.CMD_TOGGLE -> {
                    // ★状态真源在 Service（mixer），不让 VM 自己判 isPlaying（有滞后）
                    val c = coordinator.current
                    if (c == null) err("非多轨") else {
                        val nowPlaying = !c.isPlaying()
                        if (nowPlaying) {
                            c.play(); singlePlayer?.play()
                        } else {
                            c.pause(); singlePlayer?.pause()
                            coordinator.focusWasPlaying = false
                        }
                        Log.i(TAG, "CMD_TOGGLE → ${if (nowPlaying) "play" else "pause"}")
                        ok()
                    }
                }
                MultiTrackSession.CMD_SET_SPEED_PITCH -> {
                    val c = coordinator.current ?: return Futures.immediateFuture(err("非多轨"))
                    val speed = args.getFloat(MultiTrackSession.KEY_SPEED, 1f)
                    val pitchRate = args.getFloat(MultiTrackSession.KEY_PITCH_RATE, 1f)
                    lastSpeed = speed
                    lastPitchRate = pitchRate
                    c.setSpeedPitch(speed, pitchRate)
                    Log.i(TAG, "CMD_SET_SPEED_PITCH speed=$speed pitchRate=$pitchRate")
                    ok()
                }
                MultiTrackSession.CMD_SET_EQ_ENABLED -> handleSetEqEnabled(args)
                MultiTrackSession.CMD_SET_EQ_BAND -> handleSetEqBand(args)
                MultiTrackSession.CMD_RESET_EQ -> handleResetEq()
                MultiTrackSession.CMD_APPLY_EQ_TEST_CURVE -> handleApplyEqTestCurve()
                MultiTrackSession.CMD_APPLY_EQ_DEFAULT_CURVE -> handleApplyEqDefaultCurve()
                    MultiTrackSession.CMD_DEBUG_POS -> handleDebugPos()
                    else -> SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                }
            } catch (t: Throwable) {
                // 自定义命令跑在 MediaSession 主线程；任何 require/IO 异常都不能冒出去。
                Log.e(TAG, "onCustomCommand failed: ${customCommand.customAction}", t)
                runCatching { coordinator.current?.let { abortMultiLoad(it) } }
                    .onFailure { Log.w(TAG, "abortMultiLoad after command failure failed", it) }
                err("多轨命令失败: ${t.message ?: t.javaClass.simpleName}")
            }
            return Futures.immediateFuture(result)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        runCatching {
            pub.miku.neuralstem.spike.state.SeparationDomain.peek()
                ?.onPlaybackServiceLost()
        }
        handler.removeCallbacksAndMessages(null)
        eqHost.cancelRetry()
        eqHost.detachAll()   // M3-P3：EQ 随 Service 释放
        exitMultiTrackFocus()
        val closing = coordinator.current
        coordinator.current = null
        coordinator.loadGen++
        coordinator.loadPending = false
        coordinator.io.launch { runCatching { closing?.close() } }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        const val TAG = "PlaybackService"

    }
}
