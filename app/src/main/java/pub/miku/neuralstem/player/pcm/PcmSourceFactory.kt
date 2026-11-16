package pub.miku.neuralstem.player.pcm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** 把 MediaUri/本地文件转换成共享 PCM 的稳定 SourceDescriptor。 */
object PcmSourceFactory {

    private const val KEY_VERSION = "pcm-v1"

    /** 本地文件源（file:// 压缩源、私有文件）；key 与 fromMediaUri(file) 一致。 */
    fun fromFile(file: File, label: String = ""): SourceDescriptor? {
        if (!file.exists()) return null
        val uriString = Uri.fromFile(file).toString()
        return SourceDescriptor(
            cacheKey = key(uriString, file.length(), file.lastModified()),
            displayName = label.ifBlank { file.name },
            kind = SourceDescriptor.Kind.LOCAL_FILE,
            sizeBytes = file.length(),
            lastModifiedMs = file.lastModified(),
            location = file.absolutePath,
        )
    }

    fun fromMediaUri(context: Context, uri: Uri, label: String = ""): SourceDescriptor? {
        return when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: return null
                val f = File(path)
                if (!f.exists()) return null
                SourceDescriptor(
                    cacheKey = key(uri.toString(), f.length(), f.lastModified()),
                    displayName = label.ifBlank { f.name },
                    kind = SourceDescriptor.Kind.LOCAL_FILE,
                    sizeBytes = f.length(),
                    lastModifiedMs = f.lastModified(),
                    location = path,
                )
            }
            "content" -> {
                var size: Long? = null
                var mtime: Long? = null
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val si = c.getColumnIndex(OpenableColumns.SIZE)
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                            val mi = c.getColumnIndex("last_modified")
                            if (mi >= 0 && !c.isNull(mi)) mtime = c.getLong(mi)
                        }
                    }
                }
                SourceDescriptor(
                    cacheKey = key(uri.toString(), size, mtime),
                    displayName = label,
                    kind = SourceDescriptor.Kind.CONTENT_URI,
                    sizeBytes = size,
                    lastModifiedMs = mtime,
                    location = uri.toString(),
                )
            }
            else -> null
        }
    }

    private fun key(uri: String, size: Long?, mtime: Long?): String =
        "$KEY_VERSION|$uri|${size ?: -1L}|${mtime ?: -1L}"
}
