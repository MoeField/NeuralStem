package pub.miku.neuralstem.player

import android.content.Intent
import android.media.AudioManager
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
     * ★2026-11-06：多轨变速变调参数缓存。
     *
     * 单轨由 ExoPlayer 自己记；多轨要把同一组参数交给 `StemMixer`。
     * 进入多轨时用它初始化（否则会从 1.0× 开始，与 UI 显示不一致）。
     */
    @Volatile private var lastSpeed: Float = 1f
    @Volatile private var lastPitchRate: Float = 1f

    /**
     * ★2026-11-10（A3）：多轨音频焦点由 Service 统一持有。
     *
     * 门面 singlePlayer 原先 `handleAudioFocus=true`：来电/他 App 抢焦点时
     * ExoPlayer 只暂停静音门面，真正出声的 StemMixer 继续播。
     * 进入多轨时关掉门面的焦点处理，由这里 requestAudioFocus；
     * 焦点丢失 → 同时暂停 mixer + 门面；恢复 → 按丢失前状态恢复。
     */
    private var mtAudioAttributes: AudioAttributes? = null
    private var mtFocusHeld = false
    private var mtFocusWasPlaying = false
    private val mtFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        onMultiTrackFocusChange(change)
    }

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
                    onPlayerReadyForEq?.invoke(player)
                    // M3-P3：单轨(非多轨)时，把 EQ 挂到主播放器会话
                    if (!inMultiTrack) attachSingleEq(player)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // ★★ 2026-11-01 坑：**不能**用主轨的 isPlaying 去驱动 mixer。
                //   主轨（singlePlayer）现在被静音当“门面”，它自己会因为
                //   AudioTrack 暂停/缓冲而反复 isPlaying=false，
                //   直接映射会让 mixer 刚启动就被 pause（真机实测 `pause @2048frame`）。
                //   **方向反过来：mixer 才是真实的播放状态源。**
                if (inMultiTrack) {
                    Log.d(TAG, "onIsPlayingChanged($isPlaying)：多轨下忽略（真源是 StemMixer）")
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                // 单轨的真源仍是 ExoPlayer；缓存下来供进入多轨时初始化 StemMixer。
                lastSpeed = playbackParameters.speed
                lastPitchRate = playbackParameters.pitch
                if (inMultiTrack) {
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
                Log.i(TAG, "onPositionDiscontinuity: reason=$reason → ${newPosition.positionMs}ms (inMt=$inMultiTrack)")
                // ★2026-11-01：多轨下 seek 只需告知混音器（它只有一个 framePos，
                //   所有 stem 自然同步）；不再需要逐个 seek 跟随轨。
                if (inMultiTrack && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    multiTrack?.seekTo(newPosition.positionMs)
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

    /**
     * ★多轨进度推送（2026-11-01）。
     *
     * ## 为何需要单独一条
     * 重构后真实播放的是 [StemMixer]（自己的 AudioTrack），
     * 而 MediaSession 看的 `singlePlayer` 只是一个静音门面 —— 它的 `currentPosition`
     * 与混音器无关。若 UI 继续读主轨位置，进度条会停在 0 或乱跳。
     * 所以周期性把混音器位置写进 session extras，由 VM 覆盖显示。
     */
    private val progressRunnable = object : Runnable {
        override fun run() {
            val c = multiTrack
            if (inMultiTrack && c != null) {
                val session = mediaSession
                val b = Bundle(session?.sessionExtras ?: Bundle())
                b.putLong(MultiTrackSession.KEY_MIX_POS_MS, c.currentPositionMs())
                b.putLong(MultiTrackSession.KEY_MIX_DUR_MS, c.durationMs())
                b.putBoolean(MultiTrackSession.KEY_MIX_PLAYING, c.isPlaying())
                b.putBoolean(MultiTrackSession.KEY_MIX_ENDED, c.isEnded())
                session?.setSessionExtras(b)
            }
            handler.postDelayed(this, 200L)
        }
    }

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

    /**
     * 多轨 EQ：**只挂一份**在混音输出上。
     *
     * ## 2026-11-01 重构后的语义变化
     * 旧实现每条 stem 各挂一份 EQ（因为各有 AudioTrack）。
     * 现在只有一个 AudioTrack，EQ 作用于**最终混音** —— 这才是正确语义
     * （分别均衡再相加 ≠ 对混音结果均衡）。
     */
    private fun attachMultiTrackEq(c: MultiTrackController) {
        val s = c.audioSessionId()
        if (s != 0) {
            eqController.detachAll()      // 清掉单轨那份，避免两个 session 同时生效
            eqController.attach("mix", s)
            Log.i(TAG, "multi-track EQ attached to mixer session=$s")
            requestSnapshotPush()
        } else {
            Log.w(TAG, "multi-track EQ: mixer session 尚未就绪，稍后重试")
            handler.postDelayed({ attachMultiTrackEq(c) }, 200)
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
                "POS_TRACK[$i] id=${t.id} label=${t.label} uri=${t.uri}",
            )
        }
        // ★2026-11-01：只有一个 framePos，所有 stem 必然同帧 —— 报混音器位置即可
        multiTrack?.let {
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

    // ---------------- ★A3：多轨音频焦点 / 门面同步 ----------------

    /**
     * 进入多轨：门面不再自己抢焦点，改由 Service 持有。
     * 否则焦点丢失时只有静音门面被 ExoPlayer 暂停，StemMixer 继续满音量。
     */
    private fun enterMultiTrackFocus() {
        val sp = singlePlayer ?: return
        if (mtAudioAttributes == null) mtAudioAttributes = sp.audioAttributes
        // false = 不再让 ExoPlayer 管理焦点；由 mtFocusListener 统一处理。
        mtAudioAttributes?.let { sp.setAudioAttributes(it, false) }
        if (mtFocusHeld) return
        val am = getSystemService(AudioManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val granted = am.requestAudioFocus(
            mtFocusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        mtFocusHeld = granted
        Log.i(TAG, "multi-track audio focus held=$granted")
    }

    /** 退出多轨：放弃 Service 的焦点，恢复门面自己的焦点处理。 */
    private fun exitMultiTrackFocus() {
        val am = getSystemService(AudioManager::class.java)
        if (mtFocusHeld) {
            @Suppress("DEPRECATION")
            am?.abandonAudioFocus(mtFocusListener)
        }
        mtFocusHeld = false
        mtFocusWasPlaying = false
        mtAudioAttributes?.let { singlePlayer?.setAudioAttributes(it, true) }
    }

    private fun onMultiTrackFocusChange(change: Int) {
        if (!inMultiTrack) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mtFocusWasPlaying = multiTrack?.isPlaying() == true
                multiTrack?.pause()
                singlePlayer?.pause()
                mtFocusHeld = false
                Log.i(TAG, "multi-track focus lost($change) → paused (wasPlaying=$mtFocusWasPlaying)")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val resume = mtFocusWasPlaying
                mtFocusWasPlaying = false
                mtFocusHeld = true
                if (resume) {
                    multiTrack?.play()
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
        if (!inMultiTrack) return
        val c = multiTrack ?: return
        val sp = singlePlayer ?: return
        if (c.isPlaying() && !sp.isPlaying) {
            sp.play()
            Log.i(TAG, "reconcile: facade → play (mixer playing)")
        } else if (!c.isPlaying() && sp.isPlaying) {
            sp.pause()
            Log.i(TAG, "reconcile: facade → pause (mixer paused)")
        }
    }

    // ---------------- 多轨：命令处理 ----------------

    private fun handleLoadMultiTrack(args: Bundle): SessionResult {
        val json = args.getString(MultiTrackSession.KEY_ITEMS) ?: return err("items 缺失")
        val items = parseItems(json) ?: return err("items 解析失败")
        if (items.isEmpty()) return err("items 为空")

        val c = ensureMultiTrack()
        // ★2026-11-01 重构：多轨不再有“主轨 ExoPlayer”。
        //   数据全部进 StemMixer（单 AudioTrack），singlePlayer 只当**门面**：
        //   通知/前台服务/MediaController 都看它，但我们把它静音，只让它跑时钟。
        //   （为何不换成 SimpleBasePlayer：v1 用 ForwardingPlayer 实测**不出通知**，
        //    所以保留“session 认真实 ExoPlayer”这条已验证的路径。）
        singlePlayer?.volume = 0f
        c.load(items, args.getString(MultiTrackSession.KEY_TITLE))
        // ★把单轨期间/上次多轨的变速变调带给新混音器（否则会从 1.0× 开始，与 UI 不一致）。
        c.setSpeedPitch(lastSpeed, lastPitchRate)
        inMultiTrack = true
        c.playWhenAllReady(args.getLong(MultiTrackSession.KEY_START_MS, 0L))
        // ★2026-11-06：门面也必须真的在播 —— 否则 MediaSession 停在 PAUSED，
        //   UI 的 play/pause 按钮会显示「播放」而混音器实际在响（用户一按反而暂停）。
        //   之前只依赖 VM 侧的 CMD_PLAY，实测偶发丢失。
        singlePlayer?.play()
        enterMultiTrackFocus()
        attachMultiTrackEq(c)
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)      // ★开始推送 mix 进度（真实位置来自 StemMixer）
        pushTracksSnapshot()
        Log.i(
            TAG,
            "loadMultiTrack(mix): ${items.size} track(s) ids=${items.map { it.first }} " +
                "（singlePlayer 静音当门面，实际声音来自 StemMixer）",
        )
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
            )
        }
        if (items.isEmpty()) return err("items 为空")

        singlePlayer?.volume = 0f
        val c = ensureMultiTrack()
        c.loadStream(items, args.getString(MultiTrackSession.KEY_TITLE))
        c.setSpeedPitch(lastSpeed, lastPitchRate)
        inMultiTrack = true
        c.playWhenAllReady(args.getLong(MultiTrackSession.KEY_START_MS, 0L))
        singlePlayer?.play()   // 同上：门面必须与混音器同播
        enterMultiTrackFocus()
        attachMultiTrackEq(c)
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)      // ★开始推送 mix 进度
        pushTracksSnapshot()
        Log.i(TAG, "loadStream: ${items.size} streaming track(s) → StemMixer")
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
        // ★走控制器的 toggleSolo：它会重算 effectiveVolume（混音增益实时生效）
        c.toggleSolo(id)
        pushTracksSnapshot()
        return ok()
    }

    private fun handleExit(): SessionResult {
        Log.i(
            TAG,
            "EXIT_MT before: inMultiTrack=$inMultiTrack mainItem=${singlePlayer?.currentMediaItem?.localConfiguration?.uri} " +
                "followers=${multiTrack?.trackList.orEmpty().joinToString { "${it.id}:${it.uri}" }}",
        )
        inMultiTrack = false
        exitMultiTrackFocus()
        handler.removeCallbacks(progressRunnable)   // ★停止 mix 进度推送
        // 混音器释放（它不再持有任何 ExoPlayer；singlePlayer 不受影响）
        multiTrack?.close()
        multiTrack = null
        // ★恢复主轨音量：多轨期间它是 0（静音当门面），退出后必须还原，
        //   否则回原曲会“没有声音”（重构后新增的隐藏坑）。
        singlePlayer?.volume = 1f
        // M3-P3/T2：退出多轨后旧的跟随轨会话作废，回到单轨重新挂 EQ。
        // 注意：**不能**用 `playbackState == STATE_READY` 做门槛 ——
        // 主轨刚从多轨下停下，可能不是 READY，会导致 EQ **漏挂**（T2 实测踩到）。
        // `attachSingleEq` 自身会判 `audioSessionId != 0`，这里无条件调即可。
        eqController.detachAll()
        singlePlayer?.let { attachSingleEq(it) }
        pushTracksSnapshot()
        Log.i(
            TAG,
            "EXIT_MT after: inMultiTrack=$inMultiTrack mainItem=${singlePlayer?.currentMediaItem?.localConfiguration?.uri}",
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
            val commands = MultiTrackSession.ALL_COMMANDS.map { MultiTrackSession.cmd(it) }
            val builder = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            commands.forEach { builder.add(it) }
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
            if (inMultiTrack) {
                when (playerCommand) {
                    Player.COMMAND_PLAY_PAUSE -> {
                        // ★媒体键是“切换”语义；用真源 mixer 的状态判断方向。
                        //   默认命令随后还会操作门面，150ms 后 reconcile 纠正。
                        val c = multiTrack
                        if (c != null) {
                            val wasPlaying = c.isPlaying()
                            if (wasPlaying) {
                                c.pause()
                                singlePlayer?.pause()
                                mtFocusWasPlaying = false
                            } else {
                                c.play()
                                singlePlayer?.play()
                            }
                            Log.i(TAG, "media PLAY_PAUSE → ${if (wasPlaying) "pause" else "play"}")
                            handler.postDelayed({ reconcileFacadeToMixer() }, 150)
                        }
                    }
                    Player.COMMAND_SET_SPEED_AND_PITCH -> {
                        // 系统级 setPlaybackParameters（如 Android Auto/通知扩展）：
                        // 多轨真源是 StemMixer，门面参数也要同步（位置时钟）。
                        val p = singlePlayer?.playbackParameters
                        if (p != null) {
                            lastSpeed = p.speed
                            lastPitchRate = p.pitch
                            multiTrack?.setSpeedPitch(p.speed, p.pitch)
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
            Log.i(TAG, "onCustomCommand: ${customCommand.customAction}")
            val result = when (customCommand.customAction) {
                MultiTrackSession.CMD_LOAD -> handleLoadMultiTrack(args)
                MultiTrackSession.CMD_LOAD_STREAM -> handleLoadStream(args)
                MultiTrackSession.CMD_SET_VOLUME -> handleSetVolume(args)
                MultiTrackSession.CMD_TOGGLE_MUTE -> handleToggleMute(args)
                MultiTrackSession.CMD_TOGGLE_SOLO -> handleToggleSolo(args)
                MultiTrackSession.CMD_EXIT -> handleExit()
                MultiTrackSession.CMD_PLAY -> {
                    // 多轨播/停：驱动 mixer，并同步主轨（保持通知/会话状态一致）
                    multiTrack?.play()
                    singlePlayer?.play()
                    ok()
                }
                MultiTrackSession.CMD_PAUSE -> {
                    multiTrack?.pause()
                    singlePlayer?.pause()
                    mtFocusWasPlaying = false
                    ok()
                }
                MultiTrackSession.CMD_TOGGLE -> {
                    // ★状态真源在 Service（mixer），不让 VM 自己判 isPlaying（有滞后）
                    val c = multiTrack
                    if (c == null) err("非多轨") else {
                        val nowPlaying = !c.isPlaying()
                        if (nowPlaying) {
                            c.play(); singlePlayer?.play()
                        } else {
                            c.pause(); singlePlayer?.pause()
                            mtFocusWasPlaying = false
                        }
                        Log.i(TAG, "CMD_TOGGLE → ${if (nowPlaying) "play" else "pause"}")
                        ok()
                    }
                }
                MultiTrackSession.CMD_SET_SPEED_PITCH -> {
                    val c = multiTrack ?: return Futures.immediateFuture(err("非多轨"))
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
        exitMultiTrackFocus()
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
