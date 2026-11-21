package pub.miku.neuralstem.player

import android.content.Context
import android.net.Uri
import android.util.Log
import pub.miku.neuralstem.playlist.MediaSourceReader

/**
 * 公开版 AVS3/AV3A 输入检测。
 *
 * `av3a` 的 MP4 音频轨 MediaExtractor/MediaCodec 完全不认识，ExoPlayer 会表现为
 * “没有音轨 → 直接 ENDED”；而封面来自 MJPEG 轨，仍可能显示。
 *
 * 公开版没有 AVS3 解码器，因此这里只做**快速拒绝**，给用户明确原因。
 */
internal object Av3aGuard {
    private const val TAG = "Av3aGuard"

    fun isUnsupportedAv3a(context: Context, uri: Uri): Boolean {
        val name = uri.lastPathSegment ?: return false
        if (!name.lowercase().endsWith(".m4a")) return false
        return runCatching {
            val head = MediaSourceReader.readHead(context, uri) ?: return false
            Mp4CodecProbe.probeCodec(head) == "av3a"
        }.onFailure { Log.w(TAG, "probe failed: $uri", it) }
            .getOrDefault(false)
    }
}
