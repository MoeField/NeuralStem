package pub.miku.neuralstem.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 阶段 UI-V：**内嵌专辑封面提取**（黑胶盘内圈用）。
 *
 * 用 `MediaMetadataRetriever.getEmbeddedPicture` 取内嵌图（mp3/flac/m4a 常有；
 * **wav 常无** → 返回 null，UI 走"曲名环"兜底）。
 *
 * 与 [WaveformExtractor] 同款：异步、可取消（切歌时取消上次）；
 * 结果经 [StateFlow] 交给 UI。
 */
class CoverExtractor(
    private val scope: CoroutineScope,
) {

    private val _cover = MutableStateFlow<ImageBitmap?>(null)
    val cover: StateFlow<ImageBitmap?> = _cover.asStateFlow()

    var contextProvider: (() -> Context)? = null

    private var job: Job? = null

    /** 取消上次，提取新的。失败/无封面 → null（UI 走兜底）。 */
    fun extractAsync(uri: Uri) {
        val ctx = contextProvider?.invoke() ?: return
        job?.cancel()
        _cover.value = null
        job = scope.launch(Dispatchers.IO) {
            try {
                val bmp = extractBitmap(ctx, uri)
                _cover.value = bmp?.asImageBitmap()
                Log.i(TAG, "cover: ${if (bmp == null) "none" else "${bmp.width}x${bmp.height}"}")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "cover extraction failed", t)
                _cover.value = null
            }
        }
    }

    private fun extractBitmap(ctx: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val bytes = r.embeddedPicture
            if (bytes == null || bytes.isEmpty()) null
            else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            runCatching { r.release() }
        }
    }

    companion object {
        private const val TAG = "Cover"
    }
}
