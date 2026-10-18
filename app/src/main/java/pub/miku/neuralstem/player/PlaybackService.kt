package pub.miku.neuralstem.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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

    /** 多轨控制器：主轨复用 [singlePlayer]，其余为跟随轨。 */
    private var multiTrack: MultiTrackController? = null

    /** 当前是否多轨模式。 */
    private var inMultiTrack = false

    /** 主线程 handler（用于快照推送去抖）。 */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * M3-P3：EQ 控制器（**从 VM 搬进 Service**）。
     * 单轨挂 singlePlayer 会话；多轨每轨各挂一份（参数由 EqController 同步）。
     */
    private val eqController = EqController()

    override fun onCreate() {
        super.onCreate()
        // ★R2/bug C：统一构建器 → 开启 AudioTrack 原生变速变调（绕过 Sonic，避免频繁改参卡死）。
        // ★G-3：装“按 scheme 分发”的 DataSource → 同一个 player 既能播文件也能播分离流（stem://）。
        val player = buildAudioExoPlayer(
            this,
            handleAudioFocus = true,
            mediaSourceFactory = DefaultMediaSourceFactory(StemStreamFactory()),
        )
        singlePlayer = player

        // 主轨播放/暂停/seek → 同步给跟随轨（多轨模式下）
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    Log.i(TAG, "player READY, audioSessionId=${player.audioSessionId}")
                    onPlayerReadyForEq?.invoke(player)
                    // M3-P3：单轨(非多轨)时，把 EQ 挂到主播放器会话
                    if (!inMultiTrack) attachSingleEq(player)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // 跟随轨跟着主轨播/停（避免“主轨停、跟随仍在”）
                if (inMultiTrack) {
                    multiTrack?.trackList?.drop(1)?.forEach {
                        if (isPlaying) it.player.play() else it.player.pause()
                    }
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                // 变速变调同步到跟随轨（F4.4）—— 否则时间轴漂移
                if (inMultiTrack) {
                    val followers = multiTrack?.trackList?.drop(1).orEmpty()
                    Log.i(TAG, "MIRROR params → ${followers.size} follower(s): $playbackParameters")
                    followers.forEach { it.player.playbackParameters = playbackParameters }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                Log.i(TAG, "onPositionDiscontinuity: reason=$reason → ${newPosition.positionMs}ms (inMt=$inMultiTrack)")
                // seek 同步到跟随轨（seekTo 经 session 只到主轨）
                if (inMultiTrack && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    val followers = multiTrack?.trackList?.drop(1).orEmpty()
                    Log.i(TAG, "MIRROR seek → ${followers.size} follower(s): ${newPosition.positionMs}ms")
                    followers.forEach { it.player.seekTo(newPosition.positionMs) }
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

    private fun ensureMultiTrack(): MultiTrackController {
        multiTrack?.let { return it }
        val c = MultiTrackController(this)
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = requestSnapshotPush()
            override fun onPlaybackStateChanged(playbackState: Int) = requestSnapshotPush()
        })
        multiTrack = c
        return c
    }

    /**
     * 请求推送快照。
     *
     * ★ 去抖：多轨时每轨的 `onPlaybackStateChanged` 都会触发，一次 load 会
     *   连推十几次（实测）。用主线程 handler 合并成**一帧内一次**。
     */
    private fun requestSnapshotPush() {
        handler.removeCallbacks(snapshotRunnable)
        handler.post(snapshotRunnable)
    }

    private val snapshotRunnable = Runnable { pushTracksSnapshot() }

    private fun pushTracksSnapshot() {
        val session = mediaSession ?: return
        val extras = Bundle()
        if (inMultiTrack) {
            val c = multiTrack
            val arr = JSONArray()
            c?.trackList?.forEach { t ->
                arr.put(
                    JSONObject().apply {
                        put("id", t.id)
                        put("label", t.label)
                        put("volume", t.volume.toDouble())
                        put("muted", t.muted)
                        put("solo", c.soloTrackId == t.id)
                    },
                )
            }
            extras.putString(MultiTrackSession.KEY_TRACKS_JSON, arr.toString())
        }
        // M3-P3：EQ 状态快照（available/enabled/bands/levels）一起回传
        extras.putString(MultiTrackSession.KEY_EQ_JSON, eqSnapshotJson())
        session.setSessionExtras(extras)
    }

    // ---------------- M3-P3：EQ（在 Service 侧） ----------------

    /** 单轨 EQ：挂到主播放器（singlePlayer）的会话。 */
    private fun attachSingleEq(p: Player) {
        val s = p.audioSessionId
        Log.i(TAG, "attachSingleEq: audioSessionId=$s")
        if (s != 0) {
            eqController.attach("single", s)
            requestSnapshotPush()
        }
    }

    /** 多轨 EQ：每条轨各挂一份（等各自 READY 后再挂，同坑 43）。 */
    private fun attachMultiTrackEq(c: MultiTrackController) {
        c.trackList.forEach { t ->
            val tryAttach = {
                val s = t.player.audioSessionId
                if (s != 0) {
                    eqController.attach(t.id, s)
                    Log.i(TAG, "multi-track EQ attached ${t.id} session=$s")
                }
            }
            if (t.player.playbackState == Player.STATE_READY) {
                tryAttach()
            } else {
                t.player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            t.player.removeListener(this)
                            tryAttach()
                            requestSnapshotPush()
                        }
                    }
                })
            }
        }
    }

    /** EQ 状态 JSON（供 UI 渲染面板）。 */
    private fun eqSnapshotJson(): String = JSONObject().apply {
        put("available", eqController.available)
        put("enabled", eqController.isEnabled())
        put("bands", JSONArray().apply { eqController.bandFreqsHz.forEach { put(it) } })
        put("levels", JSONArray().apply { eqController.readBackLevels().forEach { put(it.toInt()) } })
    }.toString()

    private fun handleSetEqEnabled(args: Bundle): SessionResult {
        eqController.setEnabled(args.getBoolean(MultiTrackSession.KEY_EQ_ENABLED, false))
        requestSnapshotPush()
        return ok()
    }

    private fun handleSetEqBand(args: Bundle): SessionResult {
        val band = args.getInt(MultiTrackSession.KEY_EQ_BAND, -1)
        val level = args.getShort(MultiTrackSession.KEY_EQ_LEVEL_MB, 0)
        if (band < 0) return err("band 缺失")
        eqController.setBandLevel(band, level)
        requestSnapshotPush()
        return ok()
    }

    private fun handleResetEq(): SessionResult {
        eqController.reset()
        requestSnapshotPush()
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
        multiTrack?.trackList?.forEachIndexed { i, t ->
            Log.i(
                TAG,
                "POS_TRACK[$i] id=${t.id} pos=${t.player.currentPosition} " +
                    "state=${t.player.playbackState} playing=${t.player.isPlaying} " +
                    "pp=${t.player.playbackParameters}",
            )
        }
        return ok()
    }

    /** 诊断/自动化：低+6 / 中-6 / 高+6 dB 测试曲线，并开启。 */
    private fun handleApplyEqTestCurve(): SessionResult {
        val n = eqController.bandCount
        if (n == 0) return err("EQ unavailable (bandCount=0)")
        val levels = ShortArray(n)
        for (b in 0 until n) {
            val f = eqController.bandFreqsHz.getOrElse(b) { 1000 }
            levels[b] = when {
                f < 300 -> 600
                f < 3000 -> -600
                else -> 600
            }
        }
        eqController.setBandLevels(levels)
        eqController.setEnabled(true)
        requestSnapshotPush()
        Log.i(TAG, "EQ_TEST bands=$n set=${levels.toList()} readBack=${eqController.readBackLevels().toList()}")
        return ok()
    }

    /** ★Step7：应用默认微笑曲线（低+3/高+3）。 */
    private fun handleApplyEqDefaultCurve(): SessionResult {
        if (eqController.bandCount == 0) return err("EQ unavailable (bandCount=0)")
        eqController.defaultCurve()
        requestSnapshotPush()
        Log.i(TAG, "EQ_DEFAULT_CURVE readBack=${eqController.readBackLevels().toList()}")
        return ok()
    }

    // ---------------- 多轨：命令处理 ----------------

    private fun handleLoadMultiTrack(args: Bundle): SessionResult {
        val json = args.getString(MultiTrackSession.KEY_ITEMS) ?: return err("items 缺失")
        val items = parseItems(json) ?: return err("items 解析失败")
        if (items.isEmpty()) return err("items 为空")

        val primary = singlePlayer ?: return err("singlePlayer 未就绪")
        val c = ensureMultiTrack()
        // v2：主轨复用 singlePlayer（session 看的那个）—— 通知/前台服务天然工作
        c.load(items, args.getString(MultiTrackSession.KEY_TITLE), externalPrimary = primary)
        inMultiTrack = true
        c.play()                 // 主轨 + 跟随轨一起播
        // M3-P3：多轨 EQ（每轨各挂一份，等各自 READY）
        attachMultiTrackEq(c)
        pushTracksSnapshot()
        Log.i(TAG, "loadMultiTrack(v2): ${items.size} track(s), singlePlayer 为主轨")
        return ok("loaded")
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
                dataSourceFactory = StemStreamFactory(),
            )
        }
        if (items.isEmpty()) return err("items 为空")

        val primary = singlePlayer ?: return err("singlePlayer 未就绪")
        val c = ensureMultiTrack()
        c.loadStream(items, args.getString(MultiTrackSession.KEY_TITLE), externalPrimary = primary)
        inMultiTrack = true
        c.play()
        attachMultiTrackEq(c)
        pushTracksSnapshot()
        Log.i(TAG, "loadStream: ${items.size} streaming track(s), singlePlayer 为主轨")
        return ok("loaded-stream")
    }

    private fun handleSetVolume(args: Bundle): SessionResult {
        val c = multiTrack ?: return err("非多轨")
        val id = args.getString(MultiTrackSession.KEY_TRACK_ID) ?: return err("trackId 缺失")
        c.trackList.firstOrNull { it.id == id }?.volume =
            args.getFloat(MultiTrackSession.KEY_VOLUME, 1f)
        c.refreshVolumes()
        pushTracksSnapshot()
        return ok()
    }

    private fun handleToggleMute(args: Bundle): SessionResult {
        val c = multiTrack ?: return err("非多轨")
        val id = args.getString(MultiTrackSession.KEY_TRACK_ID) ?: return err("trackId 缺失")
        c.trackList.firstOrNull { it.id == id }?.let { it.muted = !it.muted }
        c.refreshVolumes()
        pushTracksSnapshot()
        return ok()
    }

    private fun handleToggleSolo(args: Bundle): SessionResult {
        val c = multiTrack ?: return err("非多轨")
        val id = args.getString(MultiTrackSession.KEY_TRACK_ID) ?: return err("trackId 缺失")
        c.soloTrackId = if (c.soloTrackId == id) null else id
        pushTracksSnapshot()
        return ok()
    }

    private fun handleExit(): SessionResult {
        inMultiTrack = false
        // singlePlayer 是主轨，不能被 multiTrack.close() release ——
        // 由 Track.ownsPlayer=false 保护。先停跟随轨，再清。
        multiTrack?.close()
        multiTrack = null
        // M3-P3/T2：退出多轨后旧的跟随轨会话作废，回到单轨重新挂 EQ。
        // 注意：**不能**用 `playbackState == STATE_READY` 做门槛 ——
        // 主轨刚从多轨下停下，可能不是 READY，会导致 EQ **漏挂**（T2 实测踩到）。
        // `attachSingleEq` 自身会判 `audioSessionId != 0`，这里无条件调即可。
        eqController.detachAll()
        singlePlayer?.let { attachSingleEq(it) }
        pushTracksSnapshot()
        Log.i(TAG, "exitMultiTrack(v2) → 单轨")
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
            val commands = MultiTrackSession.ALL_COMMANDS.map { MultiTrackSession.cmd(it) }
            val builder = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            commands.forEach { builder.add(it) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(builder.build())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            Log.i(TAG, "onCustomCommand: ${customCommand.customAction}")
            val result = when (customCommand.customAction) {
                MultiTrackSession.CMD_LOAD -> handleLoadMultiTrack(args)
                MultiTrackSession.CMD_LOAD_STREAM -> handleLoadStream(args)
                MultiTrackSession.CMD_SET_VOLUME -> handleSetVolume(args)
                MultiTrackSession.CMD_TOGGLE_MUTE -> handleToggleMute(args)
                MultiTrackSession.CMD_TOGGLE_SOLO -> handleToggleSolo(args)
                MultiTrackSession.CMD_EXIT -> handleExit()
                MultiTrackSession.CMD_SET_EQ_ENABLED -> handleSetEqEnabled(args)
                MultiTrackSession.CMD_SET_EQ_BAND -> handleSetEqBand(args)
                MultiTrackSession.CMD_RESET_EQ -> handleResetEq()
                MultiTrackSession.CMD_APPLY_EQ_TEST_CURVE -> handleApplyEqTestCurve()
                MultiTrackSession.CMD_APPLY_EQ_DEFAULT_CURVE -> handleApplyEqDefaultCurve()
                MultiTrackSession.CMD_DEBUG_POS -> handleDebugPos()
                else -> SessionResult(SessionError.ERROR_NOT_SUPPORTED)
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
        eqController.detachAll()   // M3-P3：EQ 随 Service 释放
        multiTrack?.close()
        multiTrack = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        const val TAG = "PlaybackService"

        /** M2-D EQ 钩子：真实 ExoPlayer READY 时回调（P3 会把 EQ 搬进 Service）。 */
        @Volatile
        var onPlayerReadyForEq: ((Player) -> Unit)? = null
    }
}
