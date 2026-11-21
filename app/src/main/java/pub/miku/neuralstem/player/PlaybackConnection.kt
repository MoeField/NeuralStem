package pub.miku.neuralstem.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * PlayerViewModel 与 PlaybackService 的 MediaController 连接。
 *
 * 只负责：构建/连接/释放，以及把 `MediaController.Listener` 的 extras 转发回 VM。
 * 播放状态/多轨快照/恢复逻辑仍在 PlayerViewModel，避免把业务真值搬进连接层。
 */
internal class PlaybackConnection(
    private val context: Context,
    private val onControllerReady: (MediaController) -> Unit,
    private val onExtrasChanged: (Bundle) -> Unit,
) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    fun connect() {
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val f = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                    onExtrasChanged(extras)
                }
            })
            .buildAsync()
        future = f
        f.addListener({
            try {
                val c = f.get()
                controller = c
                onControllerReady(c)
            } catch (t: Throwable) {
                Log.e(TAG, "MediaController connect failed", t)
            }
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        runCatching { controller?.release() }
        controller = null
        future?.cancel(false)
        future = null
    }

    private companion object {
        const val TAG = "PlaybackConn"
    }
}
