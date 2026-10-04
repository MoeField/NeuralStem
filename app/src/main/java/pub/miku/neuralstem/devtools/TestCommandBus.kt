package pub.miku.neuralstem.devtools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ★测试命令总线（T0）：让 `adb` 可**动作级**驱动 App，**不重启 Activity**、**不依赖坐标点击**。
 *
 * ## 为什么
 * 历史（坑 20/32、Task 0c）用零散的 `am start` + intent-extras 驱动（`auto_play`/`mt_vocals`/…）。
 * 问题：**散落各处、无统一协议、无自省**，每加一个能力就手搓一个 extra。
 * 本总线把它们收敛为**一个**入口 + **一个**执行点，且执行点调用**与 UI 点击相同的 VM 方法**
 * （保证“测试路径 = 用户路径”）。
 *
 * ## 用法（debug 构建）
 * ```
 * adb shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService \
 *     --es cmd seek --es a0 62000
 * adb shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService --es cmd help
 * ```
 * 关键优势：`start-service` **不重建 Activity** → 对“复现播放态/时序 bug”干净（`am start` 会重建）。
 * （注：首版用 `am broadcast`，在 vivo/Android16 被静默丢弃 → 改用 service。）
 *
 * ## 设计
 * - **单槽 latest-wins**：命令是“触发式事件”，不需要队列；UI 侧 collect 后消费。
 * - **不引入依赖**：纯 `MutableStateFlow`。
 * - **仅 debug**：接收器侧以 BuildConfig.DEBUG 守卫（见 TestCommandReceiver）。
 */
object TestCommandBus {

    /**
     * 一条测试命令。
     *
     * @param name 动作名（见 [helpText]）；大小写不敏感。
     * @param args 字符串参数（按下标取；数值由执行侧解析）。
     * @param delayMs 延迟执行毫秒数（0 = 立即）。★复现时序 bug 的关键（如“载入 N ms 后 seek”）。
     */
    data class Command(
        val name: String,
        val args: List<String> = emptyList(),
        val delayMs: Long = 0L,
    )

    private val _pending = MutableStateFlow<Command?>(null)

    /** 待执行命令（latest-wins）。UI/宿主 collect 后调 [consume]。 */
    val pending: StateFlow<Command?> = _pending.asStateFlow()

    /** 投递一条命令（接收器调用）。 */
    fun post(cmd: Command) {
        _pending.value = cmd
    }

    /** 消费（返回并清空）。宿主执行后调用，避免重复执行。 */
    fun consume(): Command? {
        val c = _pending.value ?: return null
        _pending.value = null
        return c
    }

    /** 自省：支持的命令清单（`--es cmd help` 返回）。★新 Agent 友好。 */
    val helpText: String = """
        NeuralStem 测试命令（adb shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService）:
          --es cmd <name> [--es a0 .. --es a3 <str|num>] [--ei delay <ms>]
        命令（动作级 = 与 UI 点击同一 VM 方法）:
          help                        打印本清单
          load    a0=uri a1=title     载入并播放（同“导入音频”）
          play                        播放
          toggle                      播放/暂停（pause 为其别名）
          seek    a0=ms               跳转
          clear                       清空当前曲（同 R4 的 clearCurrent）
          eq      a0=on(bool)         开关 EQ
          inmt    a0=vocals a1=accomp 载入多轨（abs 路径）
          exitsmt                     退出多轨
        例:
          adb shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService --es cmd seek --es a0 62000
          adb shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService --es cmd help
    """.trimIndent()

    @Deprecated("已改用 service（am start-service）；保留以便兼容引用")
    const val ACTION = "pub.miku.neuralstem.TEST"
}
