package pub.miku.neuralstem.playlist

import android.net.Uri

/**
 * 日志用 URI 脱敏：只保留 scheme + authority，抹掉 path / query。
 * path 里可能含文件名、目录、查询参数（MediaStore id 等），不应进日志。
 */
internal fun Uri.redacted(): String = "${scheme ?: "?"}://${authority ?: "-"}/<redacted>"
