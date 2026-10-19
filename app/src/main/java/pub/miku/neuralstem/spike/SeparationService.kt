package pub.miku.neuralstem.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pub.miku.neuralstem.MainActivity
import pub.miku.neuralstem.R

/**
 * ★G-4：分离任务的**前台服务**（`foregroundServiceType=dataSync`）。
 *
 * ## 为什么需要
 * 分离任务由 [SeparationRepository]（进程级单例 + 应用级协程作用域）执行，本就跨页面存活；
 * 但**没有前台服务** → 应用切后台被放入 `/background` cgroup → **降频 ~2×**
 * （实测 4 分钟歌 30.7s → 64.7s，超 60s 门槛，见 `.memory.md` V4）。
 *
 * 本服务在分离窗口内把进程**提升为前台优先级**，从而后台也接近前台速度。
 *
 * ## 生命周期（唯一职责：只覆盖"分离窗口"）
 * - 由 [SeparationRepository.start]/[start4]/[startDemucs] 在**真正开跑前**调 [start]。
 * - [onStartCommand] → `startForeground(...)` → 收集 [SeparationRepository.state]：
 *   - `Preparing`/`Running` → 刷新进度通知；
 *   - `Done`/`Done4`/`Failed`/`Cancelled`/`Idle` → **`stopSelf()`**（不常驻）。
 * - 播放用的 [pub.miku.neuralstem.player.PlaybackService]（mediaPlayback）**独立存在、不受影响**。
 *
 * ## 失败降级
 * 若 `startForeground` 抛异常（部分 ROM 限制），**只记日志、不阻断分离**——
 * 分离仍在 [SeparationRepository] 里照常跑，只是可能被降频。
 */
class SeparationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        try {
            // type 参数仅 API 29+ 使用；低版本传 0 即可（兼容由 ServiceCompat 处理）。
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(0, null),
                type,
            )
        } catch (t: Throwable) {
            // 降级：前台起不来也照跑分离（仅可能被降频）。
            Log.w(TAG, "startForeground failed → 分离继续但可能降频", t)
        }

        observeState()
        return START_NOT_STICKY
    }

    /** 订阅仓库状态：刷新进度 / 终态自停。 */
    private fun observeState() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            SeparationRepository.get(application).state.collectLatest { s ->
                when (s) {
                    is SeparationRepository.State.Preparing -> {
                        notifyProgress(0, "准备中…")
                    }
                    is SeparationRepository.State.Running -> {
                        notifyProgress(s.percent, "分离中 ${s.percent}%")
                    }
                    is SeparationRepository.State.Done,
                    is SeparationRepository.State.Done4,
                    is SeparationRepository.State.EdgeDone,
                    is SeparationRepository.State.Failed,
                    is SeparationRepository.State.Cancelled,
                    is SeparationRepository.State.Idle -> {
                        stopSelfCleanly()
                    }
                }
            }
        }
    }

    private fun notifyProgress(percent: Int, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotification(percent, text))
    }

    /** 只停一次的 `stopSelf`（避免多终态重复触发）。 */
    private fun stopSelfCleanly() {
        if (stopped) return
        stopped = true
        Log.i(TAG, "separation terminal → stopSelf")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- 通知 ----------------

    private fun buildNotification(percent: Int, text: String?): Notification {
        val content = text ?: "准备中…"
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.sep_notif_title))
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
        if (percent in 1..100) {
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true) // 不确定态（准备中）
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sep_notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = getString(R.string.sep_notif_channel)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "SepService"
        private const val CHANNEL_ID = "separation"
        private const val NOTIF_ID = 0x5EA0

        /**
         * 起前台服务（分离开跑前调用）。**失败不抛出**（降级为无前台照跑）。
         *
         * @return true = 已成功请求启动（不代表 startForeground 一定成功）。
         */
        fun start(context: Context): Boolean {
            return try {
                val i = Intent(context, SeparationService::class.java)
                ContextCompat.startForegroundService(context, i)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "startForegroundService failed (ignore, run without foreground)", t)
                false
            }
        }
    }
}
