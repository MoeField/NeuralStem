package pub.miku.neuralstem.devtools

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★屏上日志（debug 工具）：把关键日志**同时**收进内存环形缓冲，供 UI 显示。
 *
 * ## 为什么需要
 * 真机（尤其 vivo ROM）会**吞掉 app 的 `logcat`** —— Gen3 上实测连 `Log.i` 都不出。
 * 而验收必须走“非 logcat 通道”。本缓冲让日志**显示在测试面板上**（截图即证据）。
 *
 * ## 用法
 * - 模块侧：`TestLog.log("Spk", "msg")`（等价于 `Log.i`，**额外**进缓冲）。
 * - UI 侧：`collect(TestLog.lines)` 渲染滚动框。
 *
 * 仅 debug 有意义（release 也不会从 UI 展示）；缓冲限 [MAX] 行。
 */
object TestLog {

    private const val MAX = 400
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** 记一条（同时进 logcat 与缓冲）。 */
    fun log(tag: String, msg: String) {
        Log.i(tag, msg)
        _lines.value = (_lines.value + stamp(tag, msg)).takeLast(MAX)
    }

    /** 仅追加（不进 logcat，避免重复）。 */
    fun append(tag: String, msg: String) {
        _lines.value = (_lines.value + stamp(tag, msg)).takeLast(MAX)
    }

    fun clear() {
        _lines.value = emptyList()
    }

    private fun stamp(tag: String, msg: String) = "${fmt.format(Date())}  [$tag] $msg"
}
