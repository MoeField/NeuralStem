package pub.miku.neuralstem.devtools

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * ★仅 debug：把边播 Gate / 混音器的关键状态写进文件（2026-11-06）。
 *
 * ## 为什么不用 logcat
 * 本机（vivo / Android 16）**吞 App 的 logcat** —— `adb logcat` 里几乎看不到
 * `StemMixer` / `gate` 的日志（历史测试全靠 `dumpsys` / 屏上日志取证）。
 * 自动化验证「Gate 是否真的暂停了混音器」需要一条可靠通道，所以写文件：
 *
 * ```
 * adb shell run-as pub.miku.neuralstem cat files/edge_probe.txt
 * ```
 *
 * ## 线程模型（重要）
 * `StemMixer` 的心跳在**音频渲染线程**上调用 [log]。文件 IO 绝不能在那条线程做
 * （几毫秒的写盘就可能 underrun），所以这里用**单消费者队列 + 后台线程**：
 * - [log] 只做 `offer`（非阻塞）+ `Log.i`；
 * - 后台 daemon 线程负责格式化时间与 append，进程退出时不管（debug 工具）。
 *
 * ## 约束
 * - **仅 debug**（`BuildConfig.DEBUG`），release 不产生任何 IO；
 * - 每次进程启动清空，避免旧行干扰判读。
 */
object EdgeProbe {

    private const val TAG = "EdgeProbe"
    private const val FILE_NAME = "edge_probe.txt"
    private const val QUEUE_CAP = 512

    private var file: File? = null

    /** 非阻塞队列：音频线程只 offer，绝不等待。满了就丢（取证不需要绝对完整）。 */
    private val queue = ArrayBlockingQueue<String>(QUEUE_CAP)

    @Volatile
    private var writerStarted = false

    fun init(context: Context) {
        if (!pub.miku.neuralstem.BuildConfig.DEBUG) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        runCatching { f.writeText("") }
        if (writerStarted) return
        writerStarted = true
        Thread({
            val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            while (true) {
                val line = runCatching { queue.poll(1, TimeUnit.SECONDS) }.getOrNull() ?: continue
                runCatching { f.appendText("${fmt.format(Date())} $line\n") }
            }
        }, "EdgeProbeWriter").apply { isDaemon = true }.start()
    }

    fun log(tag: String, msg: String) {
        if (!pub.miku.neuralstem.BuildConfig.DEBUG) return
        Log.i(TAG, "$tag $msg")
        if (file == null) return
        // 非阻塞：队列满时丢弃最旧的一条都不做（避免在音频线程做任何等待）。
        queue.offer("[$tag] $msg")
    }
}
