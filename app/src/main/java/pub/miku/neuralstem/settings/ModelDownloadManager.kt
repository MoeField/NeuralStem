package pub.miku.neuralstem.settings

import android.app.Application
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 进程级模型下载管理器。
 *
 * ## 为何需要
 * 旧实现把 `ModelDownloader` 和唯一的 `downloadJob` 放在 `ModelLibraryScreen`
 * 的 composition 里：离开页面 → `rememberCoroutineScope` 取消 → 下载断掉；
 * 且只有一个 job 变量，取消 A 会误取消 B。
 *
 * 本类：
 * - 单例活在进程内；页面/Activity 重建不影响任务；
 * - 每个 [ModelPack] 一个 job，取消/失败互不影响；
 * - 状态仍是 [ModelDownloader] 的 `Map<ModelPack, State>`，UI 直接 collect。
 *
 * 进程被杀仍会中断；`.part` 保留，下次 start 走 Range 续传。
 */
class ModelDownloadManager private constructor(
    private val downloader: ModelDownloader,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val jobs = ConcurrentHashMap<ModelPack, Job>()

    val states: StateFlow<Map<ModelPack, ModelDownloader.State>> = downloader.states
    val lastProbedBaseUrl: String? get() = downloader.lastProbedBaseUrl

    fun stateOf(pack: ModelPack): ModelDownloader.State = downloader.stateOf(pack)
    fun isDownloading(pack: ModelPack): Boolean = jobs[pack]?.isActive == true

    /** 启动一个包的下载；同一包已在跑则不重复启动。 */
    fun start(pack: ModelPack, pref: ModelSourcePreference) {
        synchronized(lock) {
            if (jobs[pack]?.isActive == true) {
                Log.i(TAG, "start($pack) ignored: already running")
                return
            }
            val job = scope.launch {
                try {
                    downloader.download(pack, pref)
                } finally {
                    synchronized(lock) {
                        if (jobs[pack] === coroutineContext[Job]) jobs.remove(pack)
                    }
                }
            }
            jobs[pack] = job
        }
        Log.i(TAG, "start($pack), jobs=${jobs.size}")
    }

    /** 只取消这个包；其他包的 job 不受影响。 */
    fun cancel(pack: ModelPack) {
        val job = jobs[pack]
        Log.i(TAG, "cancel($pack), active=${job?.isActive == true}")
        job?.cancel()
    }

    fun delete(pack: ModelPack): Boolean = downloader.delete(pack)

    fun clearProbeCache() = downloader.clearProbeCache()

    companion object {
        private const val TAG = "ModelDlMgr"

        @Volatile
        private var instance: ModelDownloadManager? = null

        fun get(app: Application): ModelDownloadManager =
            instance ?: synchronized(this) {
                instance ?: ModelDownloadManager(
                    downloader = ModelDownloader(app.filesDir).apply {
                        proxyProvider = { SettingsRepository(app).downloadProxy }
                    },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                ).also { instance = it }
            }
    }
}
