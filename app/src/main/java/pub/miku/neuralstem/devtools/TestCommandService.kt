package pub.miku.neuralstem.devtools

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import pub.miku.neuralstem.BuildConfig

/**
 * ★测试命令入口（T0，**Service 版**）：把 `adb am start-service` 的意图翻译成
 * [TestCommandBus.Command] 投递。
 *
 * ```
 * adb shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService \
 *     --es cmd seek --es a0 62000
 * adb shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService \
 *     --es cmd help
 * ```
 *
 * ## 为何是 **Service** 而不是 **BroadcastReceiver**
 * 首版用 `am broadcast` + manifest receiver，在 **vivo / Android 16** 上被 **静默丢弃**
 * （AMS 报 “Broadcasting/Enqueued”，但 `onReceive` 从不执行；**OEM 限制**，
 * 与项目已知的坑 52「vivo 静默拦截 adb install」同类）。
 * `am start-service` **不受广播限制**，且**不重建 Activity** → 对“复现播放态/时序 bug”干净。
 *
 * ## 安全
 * - **仅 debug**：非 [BuildConfig.DEBUG] 直接忽略（发布版即便被启动也无行为）。
 * - 只做“投递”，不做任何敏感操作；实际动作由宿主（MainActivity）落到 VM。
 * - 参数里的路径/uri 受 App 自身权限约束（无法越权）。
 */
class TestCommandService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent
        if (!BuildConfig.DEBUG || i == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val name = i.getStringExtra("cmd")?.trim().orEmpty()
        if (name.isEmpty()) {
            Log.w(TAG, "TEST 命令缺少 --es cmd；可用 --es cmd help 查看清单")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val args = (0..3).mapNotNull { i.getStringExtra("a$it") }
        // 兼容 --el（long）与 --ei（int）：两者都试，取非 0 者。
        val delayLong = i.getLongExtra("delay", 0L)
        val delayMs = if (delayLong != 0L) delayLong else i.getIntExtra("delay", 0).toLong()
        val cmd = TestCommandBus.Command(name.lowercase(), args, delayMs)
        Log.i(TAG, "TEST <- $cmd")
        TestCommandBus.post(cmd)
        stopSelf(startId) // 一次性：投递完即停（避免常驻）
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "TestCmd"

        /** 便捷构造（供测试/代码内触发）。 */
        fun intentFor(context: Context, cmd: TestCommandBus.Command): Intent =
            Intent(context, TestCommandService::class.java)
                .putExtra("cmd", cmd.name)
                .putExtra("delay", cmd.delayMs)
                .apply { cmd.args.forEachIndexed { i, a -> putExtra("a$i", a) } }
    }
}
