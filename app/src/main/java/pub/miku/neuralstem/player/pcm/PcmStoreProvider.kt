package pub.miku.neuralstem.player.pcm

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 进程级 PcmStore 单例；不依赖 Activity/Compose。 */
object PcmStoreProvider {
    @Volatile private var instance: PcmStore? = null

    fun get(context: Context): PcmStore {
        return instance ?: synchronized(this) {
            instance ?: create(context).also { instance = it }
        }
    }

    private fun create(context: Context): PcmStore {
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        return PcmStore(
            cacheDir = File(app.filesDir, "pcm-cache"),
            scope = scope,
            decoder = PcmDecodePipeline(app),
            maxBytes = 12L * 1024L * 1024L * 1024L,
        )
    }
}
