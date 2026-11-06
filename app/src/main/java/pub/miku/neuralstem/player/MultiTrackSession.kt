package pub.miku.neuralstem.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * 多轨控制的 **UI ↔ Service 协议**（阶段 M3 / P2）。
 *
 * ## 为什么不用 `MediaController` 标准 API
 * 多轨的「各轨音量 / 静音 / 独奏」是**本项目自定义概念**，media3 的 `Player`
 * 接口里没有对应方法 → 走 media3 的**自定义命令**机制：
 * `MediaController.sendCustomCommand(...)` ↔ `MediaSession.Callback.onCustomCommand(...)`
 * （设计文档 tech_design_multitrack_service §3.3）
 *
 * ## 两份“契约”
 * 1. **命令**（UI → Service）：见 [CMD_*] 常量
 * 2. **状态快照**（Service → UI）：[KEY_TRACKS_JSON]（事件驱动，经 `setSessionExtras`）
 *    用 JSON 字符串承载，避免 Bundle 嵌套结构的序列化坑。
 */
object MultiTrackSession {

    // ---------------- 命令：UI → Service ----------------

    /** 载入多轨。args: [KEY_ITEMS]（JSON 数组，每项 {id,label,uri}）。 */
    const val CMD_LOAD = "nst.loadMultiTrack"

    /**
     * ★G-3：载入**流式**多轨（边分离边播）。args: [KEY_ITEMS]（JSON，每项 {id,label,host}）。
     * 每轨 uri 由 Service 拼为 `stem://<host>`；ring buffer 同进程共享（EdgeSeparationSession）。
     */
    const val CMD_LOAD_STREAM = "nst.loadStreamMultiTrack"

    /** 设某轨音量。args: [KEY_TRACK_ID], [KEY_VOLUME]。 */
    const val CMD_SET_VOLUME = "nst.setTrackVolume"

    /** 切换某轨静音。args: [KEY_TRACK_ID]。 */
    const val CMD_TOGGLE_MUTE = "nst.toggleMute"

    /** 切换某轨独奏。args: [KEY_TRACK_ID]。 */
    const val CMD_TOGGLE_SOLO = "nst.toggleSolo"

    /** 退出多轨（回到单轨）。无 args。 */
    const val CMD_EXIT = "nst.exitMultiTrack"

    // ★2026-11-01：多轨播/停必须**显式下发**。
    //
    // ## 为何不能用“镜像主轨 isPlaying”
    // 多轨时主轨 ExoPlayer 只是静音门面，本身会因缓冲/静音轨而反复抖动；
    // 用它驱动 mixer 会导致“刚启动就被 pause”（真机实测）。
    // 所以播/停指令一律由 UI → VM → Service 显式传递。
    const val CMD_PLAY = "nst.playMultiTrack"
    const val CMD_PAUSE = "nst.pauseMultiTrack"

    /**
     * 多轨切换播/停（**由 Service 自己判断当前状态**）。
     *
     * ## 为何需要它，而不是 VM 自己判 isPlaying 再发 PLAY/PAUSE
     * VM 的 `isPlaying` 来自 extras（200ms 一次），有滞后与瞬时不一致。
     * 曾经出现过“点了暂停没反应”（VM 以为它在播 → 其实状态已过期）。
     * 状态的真源是 Service 里的 mixer，toggle 就该由它自己判。
     */
    const val CMD_TOGGLE = "nst.toggleMultiTrack"

    /**
     * ★2026-11-06：多轨变速变调。args: [KEY_SPEED]（倍率）, [KEY_PITCH_RATE]（倍率）。
     *
     * ## 为何不能用 `mediaController.playbackParameters`
     * 那会把参数下发给**静音门面** ExoPlayer，触发它的 AudioSink 重配
     * （坑 48：频繁改参 → ERROR_CODE_TIMEOUT），而真正发声的 StemMixer 收不到。
     * 所以多轨必须走这条自定义命令，由 Service 转给 StemMixer。
     */
    const val CMD_SET_SPEED_PITCH = "nst.setSpeedPitch"

    // ---- M3-P3：EQ 控制（EQ 已搬进 Service）----

    /** EQ 开关。args: [KEY_EQ_ENABLED]。 */
    const val CMD_SET_EQ_ENABLED = "nst.setEqEnabled"

    /** 设某段增益(mB)。args: [KEY_EQ_BAND], [KEY_EQ_LEVEL_MB]。 */
    const val CMD_SET_EQ_BAND = "nst.setEqBand"

    /** EQ 复位（全零）。 */
    const val CMD_RESET_EQ = "nst.resetEq"

    /** 诊断/自动化：应用测试曲线并开启。 */
    const val CMD_APPLY_EQ_TEST_CURVE = "nst.applyEqTestCurve"

    /** ★Step7：应用默认曲线（低+3dB / 高+3dB，“微笑”），首次开启 EQ 时用。 */
    const val CMD_APPLY_EQ_DEFAULT_CURVE = "nst.applyEqDefaultCurve"

    /** **M4/S1 诊断**：日志打印每条轨的 `currentPosition`（验证多轨 seek 后对齐）。 */
    const val CMD_DEBUG_POS = "nst.debugPos"

    /**
     * 所有自定义命令的**白名单**。
     *
     * ## ★新增命令必须加进来（2026-11-01 踩过）
     * `MediaSession` 只接受在 `onConnect` 里声明过的 session commands。
     * 不在这个列表里的命令，`MediaController.sendCustomCommand()` 会**静默失败**
     * （返回的 future 有异常，但我们没监听）→ 表现为“点了没反应、日志也没有”。
     *
     * 实测：新增 CMD_TOGGLE 后忘了加这里，暂停按钮完全无效，
     * Service 侧连 `onCustomCommand` 都不打印。
     */
    val ALL_COMMANDS = listOf(
        CMD_LOAD, CMD_LOAD_STREAM, CMD_SET_VOLUME, CMD_TOGGLE_MUTE, CMD_TOGGLE_SOLO, CMD_EXIT,
        CMD_PLAY, CMD_PAUSE, CMD_TOGGLE, CMD_SET_SPEED_PITCH,
        CMD_SET_EQ_ENABLED, CMD_SET_EQ_BAND, CMD_RESET_EQ, CMD_APPLY_EQ_TEST_CURVE,
        CMD_APPLY_EQ_DEFAULT_CURVE, CMD_DEBUG_POS,
    )

    // ---------------- 参数 / 快照键 ----------------

    const val KEY_ITEMS = "items"
    const val KEY_TRACK_ID = "trackId"
    const val KEY_VOLUME = "volume"

    /** 多轨变速/变调（倍率，1.0 = 原速/原调）。 */
    const val KEY_SPEED = "nst.speed"
    const val KEY_PITCH_RATE = "nst.pitchRate"

    /** 曲目显示名（通知标题 = 主轨曲目名）。 */
    const val KEY_TITLE = "title"

    /**
     * ★对齐起播位置（ms）。
     *
     * ## 为何必须传给 Service 而不是在 VM 里 seek
     * VM 的 READY 回调只能 seek **主轨**（mediaController 就是 singlePlayer），
     * 跟随轨会留在 0 → 两边错位几秒（真机实测）。
     * 现由 Service 对所有轨下同一位置（见 [MultiTrackController.playWhenAllReady]）。
     */
    const val KEY_START_MS = "startMs"

    // ==================== ★多轨进度（2026-11-01 单 AudioTrack 重构）====================
    //
    // ## 为何需要单独的 key
    // 多轨时真实播放的是 StemMixer（自己的 AudioTrack），
    // 而 session 看的 `singlePlayer` 只是静音门面，位置无关。
    // Service 每 200ms 把 mixer 位置写进 extras，VM 优先用它。
    const val KEY_MIX_POS_MS = "nst.mixPosMs"
    const val KEY_MIX_DUR_MS = "nst.mixDurMs"
    const val KEY_MIX_PLAYING = "nst.mixPlaying"

    /**
     * ★2026-11-06：混音器是否已到曲尾。
     *
     * 变速后混音器的媒体时间与门面 ExoPlayer 不同步（2× 时混音器先结束），
     * 不能只靠门面的 STATE_ENDED 自动切下一首 —— 否则会白等半首歌。
     */
    const val KEY_MIX_ENDED = "nst.mixEnded"

    /** Service → UI 的多轨状态快照（JSON）。无该键 = 非多轨模式。 */
    const val KEY_TRACKS_JSON = "nst.tracksJson"

    // ---- M3-P3：EQ 相关键 ----

    /** EQ 开关（Bundle boolean）。 */
    const val KEY_EQ_ENABLED = "nst.eqEnabled"

    /** EQ 段下标。 */
    const val KEY_EQ_BAND = "nst.eqBand"

    /** EQ 段增益（mB）。 */
    const val KEY_EQ_LEVEL_MB = "nst.eqLevelMb"

    /**
     * EQ 状态快照（JSON）：{available, enabled, bands:[freqHz], levels:[mB]}。
     * Service → UI，事件驱动（随多轨快照一起 push）。
     */
    const val KEY_EQ_JSON = "nst.eqJson"

    /** 构造一个命令。 */
    fun cmd(name: String, args: Bundle = Bundle.EMPTY): SessionCommand =
        SessionCommand(name, args)
}
