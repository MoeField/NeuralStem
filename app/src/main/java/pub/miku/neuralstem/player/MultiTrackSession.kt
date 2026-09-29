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

    /** 设某轨音量。args: [KEY_TRACK_ID], [KEY_VOLUME]。 */
    const val CMD_SET_VOLUME = "nst.setTrackVolume"

    /** 切换某轨静音。args: [KEY_TRACK_ID]。 */
    const val CMD_TOGGLE_MUTE = "nst.toggleMute"

    /** 切换某轨独奏。args: [KEY_TRACK_ID]。 */
    const val CMD_TOGGLE_SOLO = "nst.toggleSolo"

    /** 退出多轨（回到单轨）。无 args。 */
    const val CMD_EXIT = "nst.exitMultiTrack"

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

    val ALL_COMMANDS = listOf(
        CMD_LOAD, CMD_SET_VOLUME, CMD_TOGGLE_MUTE, CMD_TOGGLE_SOLO, CMD_EXIT,
        CMD_SET_EQ_ENABLED, CMD_SET_EQ_BAND, CMD_RESET_EQ, CMD_APPLY_EQ_TEST_CURVE,
        CMD_APPLY_EQ_DEFAULT_CURVE, CMD_DEBUG_POS,
    )

    // ---------------- 参数 / 快照键 ----------------

    const val KEY_ITEMS = "items"
    const val KEY_TRACK_ID = "trackId"
    const val KEY_VOLUME = "volume"

    /** 曲目显示名（通知标题 = 主轨曲目名）。 */
    const val KEY_TITLE = "title"

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
