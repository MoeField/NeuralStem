package pub.miku.neuralstem.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import org.json.JSONArray

/**
 * v0.3.1 Step 4：多轨自定义命令处理。
 *
 * 从 `PlaybackService.SessionCallback.onCustomCommand` 抽出的 when 分派 + 各命令实现。
 * **UID 安全闸不在这里**——`onCustomCommand` 最外层仍是唯一入口
 * （`controller.uid == Process.myUid()`），本类只处理已通过校验的命令。
 *
 * 依赖全部通过构造注入（coordinator/eqHost/ExoPlayer 提供者/参数回调），
 * 不持有 Service、不碰 MediaSession 生命周期。
 */
internal class PlayerCommandHandler(
    private val coordinator: MultiTrackCoordinator,
    private val getSinglePlayer: () -> ExoPlayer?,
    private val eqHost: EqHost,
    /** 更新 Service 缓存的变速变调（进入多轨时初始化混音器用）。 */
    private val onSpeedPitchChanged: (speed: Float, pitchRate: Float) -> Unit,
    /** 命令抛异常时清场（对应原 catch 里的 abortMultiLoad）。 */
    private val onCommandFailure: (MultiTrackController) -> Unit,
    private val tag: String = "PlaybackService",
) {

    fun dispatch(action: String, args: Bundle): SessionResult = try {
        when (action) {
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
                if (coordinator.loadPending) {
                    ok("loading")
                } else {
                    coordinator.current?.play()
                    getSinglePlayer()?.play()
                    ok()
                }
            }
            MultiTrackSession.CMD_PAUSE -> {
                if (coordinator.loadPending) {
                    ok("loading")
                } else {
                    coordinator.current?.pause()
                    getSinglePlayer()?.pause()
                    coordinator.focusWasPlaying = false
                    ok()
                }
            }
            MultiTrackSession.CMD_TOGGLE -> {
                // ★状态真源在 mixer，不让 VM 自己判 isPlaying（有滞后）
                val c = coordinator.current
                if (c == null) {
                    err("非多轨")
                } else {
                    val nowPlaying = !c.isPlaying()
                    if (nowPlaying) {
                        c.play()
                        getSinglePlayer()?.play()
                    } else {
                        c.pause()
                        getSinglePlayer()?.pause()
                        coordinator.focusWasPlaying = false
                    }
                    Log.i(tag, "CMD_TOGGLE → ${if (nowPlaying) "play" else "pause"}")
                    ok()
                }
            }
            MultiTrackSession.CMD_SET_SPEED_PITCH -> {
                val c = coordinator.current ?: return err("非多轨")
                val speed = args.getFloat(MultiTrackSession.KEY_SPEED, 1f)
                val pitchRate = args.getFloat(MultiTrackSession.KEY_PITCH_RATE, 1f)
                onSpeedPitchChanged(speed, pitchRate)
                c.setSpeedPitch(speed, pitchRate)
                Log.i(tag, "CMD_SET_SPEED_PITCH speed=$speed pitchRate=$pitchRate")
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
        Log.e(tag, "onCustomCommand failed: $action", t)
        coordinator.current?.let { onCommandFailure(it) }
        err("多轨命令失败: ${t.message ?: t.javaClass.simpleName}")
    }

    // ---------------- 加载 ----------------

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
        } catch (t: Throwable) {
            return err("items 解析失败")
        }
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

    private fun parseItems(json: String): List<Triple<String, String, Uri>>? = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Triple(o.getString("id"), o.getString("label"), Uri.parse(o.getString("uri")))
        }
    }.getOrNull()

    // ---------------- 音量 / 静音 / 独奏 / 退出 ----------------

    private fun handleSetVolume(args: Bundle): SessionResult {
        val id = args.getString(MultiTrackSession.KEY_TRACK_ID) ?: return err("trackId 缺失")
        if (!coordinator.setVolume(id, args.getFloat(MultiTrackSession.KEY_VOLUME, 1f))) {
            return err("非多轨")
        }
        return ok()
    }

    private fun handleToggleMute(args: Bundle): SessionResult {
        val id = args.getString(MultiTrackSession.KEY_TRACK_ID) ?: return err("trackId 缺失")
        if (!coordinator.toggleMute(id)) return err("非多轨")
        return ok()
    }

    private fun handleToggleSolo(args: Bundle): SessionResult {
        val id = args.getString(MultiTrackSession.KEY_TRACK_ID) ?: return err("trackId 缺失")
        if (!coordinator.toggleSolo(id)) return err("非多轨")
        return ok()
    }

    private fun handleExit(): SessionResult {
        Log.i(
            tag,
            "EXIT_MT before: active=${coordinator.active} " +
                "followers=${coordinator.current?.trackList.orEmpty().joinToString { "${it.id}:${it.uri}" }}",
        )
        coordinator.exit()
        Log.i(tag, "EXIT_MT after: active=${coordinator.active}")
        return ok()
    }

    // ---------------- EQ ----------------

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

    /** 诊断/自动化：低+6 / 中-6 / 高+6 dB 并开启。 */
    private fun handleApplyEqTestCurve(): SessionResult {
        if (!eqHost.applyTestCurve()) return err("EQ unavailable (bandCount=0)")
        return ok()
    }

    /** ★Step7：应用默认微笑曲线（低+3/高+3）。 */
    private fun handleApplyEqDefaultCurve(): SessionResult {
        if (!eqHost.applyDefaultCurve()) return err("EQ unavailable (bandCount=0)")
        return ok()
    }

    // ---------------- 诊断 ----------------

    /**
     * M4/S1 诊断：打印主轨 `currentPosition` / 多轨位置。
     * 只有一个 framePos，所有 stem 必然同帧 —— 报混音器位置即可。
     */
    private fun handleDebugPos(): SessionResult {
        val main = getSinglePlayer()
        Log.i(
            tag,
            "POS_MAIN pos=${main?.currentPosition} state=${main?.playbackState} " +
                "playing=${main?.isPlaying} pp=${main?.playbackParameters}",
        )
        coordinator.current?.trackList?.forEachIndexed { i, t ->
            Log.i(tag, "POS_TRACK[$i] id=${t.id} label=${t.label} uri=${t.uri}")
        }
        coordinator.current?.let {
            Log.i(
                tag,
                "POS_MIX pos=${it.currentPositionMs()} dur=${it.durationMs()} " +
                    "playing=${it.isPlaying()} tracks=${it.trackList.size}",
            )
        }
        return ok()
    }
}
