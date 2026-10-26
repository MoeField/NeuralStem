package pub.miku.neuralstem.experimental.ncm

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * ★AVS3 解码产物的**磁盘缓存**（WAV 直存）。
 *
 * ## 为什么需要
 * 一首 4 分钟的歌解码要 **~42 秒**（RTF 6.6×）。如果不缓存，
 * 每次点播都要等这么久 —— 这是最伤体验的一点。
 * 解好存成 WAV 后，第二次打开直接交给播放器，**秒开**。
 *
 * ## 为什么直接存 WAV 而不压缩
 *
 * | 方案 | 体积 | CPU | 复杂度 |
 * |---|---|---|---|
 * | **WAV 直存** | 42MB/首 | 0（就是原始 PCM） | 低 |
 * | 转 FLAC | ~25MB/首 | 编码 + 解码 | 高 |
 * | 转 Opus | ~5MB/首 | 编码 + 解码（有损） | 高 |
 *
 * 用户明确说「方便处理优先，大小不管」→ 选 WAV 直存：
 * 写出去就是 `writeBytes`，读回来直接给 `WavExtractor`，**零转换成本**。
 *
 * ## 淘汰策略
 * - **LRU**（按 lastModified），总量超过上限时从最旧的开始删
 * - 上限由设置页的档位决定（1/2/3/4 GB）
 * - **不需要「重置 App」**：用户把档位调小后，下次写入会自动淘汰到新上限以下；
 *   设置页也提供「清除解码缓存」立即释放。
 *
 * ## 缓存键
 * 用「源文件路径 + 大小 + 修改时间」做 SHA-256 前 8 字节。
 * 这样同一首歌重复打开能命中；而文件被替换/重新下载时会自动失效，
 * 不会放出旧音频。
 */
object Avs3PcmCache {

    private const val TAG = "Avs3PcmCache"

    private const val DIR = "avs3-pcm-cache"

    /** 缓存文件后缀。存的是完整 WAV（44 字节头 + PCM）。 */
    private const val EXT = ".wav"

    /** WAV 头长度；小于等于它的文件视为损坏。 */
    private const val WAV_HEADER = 44L

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /**
     * 生成缓存键。
     *
     * 用「路径 + 大小 + 修改时间」做 SHA-256 取前 8 字节。
     * 不用 `String.hashCode()`：那只有 32 位，几十首歌就可能撞，
     * 撞了就会放出**错误的音频**（比不解码还糟）。
     */
    fun keyFor(srcPath: String, sizeBytes: Long, lastModified: Long): String {
        val raw = "$srcPath|$sizeBytes|$lastModified"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** 缓存文件（不保证存在）。 */
    fun fileFor(context: Context, key: String): File = File(dir(context), "$key$EXT")

    /** 命中则返回文件，否则 null。 */
    fun get(context: Context, key: String): File? {
        val f = fileFor(context, key)
        if (!f.exists() || f.length() <= WAV_HEADER) return null
        // 触摸一下，让 LRU 认为它最近被用过。
        runCatching { f.setLastModified(System.currentTimeMillis()) }
        Log.i(TAG, "命中 $key（${"%.1f".format(f.length() / 1_048_576.0)}MB）")
        return f
    }

    /**
     * 写入缓存。先写 `.tmp` 再 rename，避免中途失败留下半个文件
     * （半个文件会被 WavExtractor 当成长度不足的 WAV，比没有更糟）。
     *
     * @param limitBytes 总上限；写完会按 LRU 淘汰到上限以下
     */
    fun put(context: Context, key: String, wavBytes: ByteArray, limitBytes: Long): File? {
        return runCatching {
            val target = fileFor(context, key)
            val tmp = File(target.parentFile, "$key.tmp")
            tmp.writeBytes(wavBytes)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) error("rename 失败")
            Log.i(TAG, "写入 $key（${"%.1f".format(wavBytes.size / 1_048_576.0)}MB）")
            evictIfNeeded(context, limitBytes, keep = target)
            target
        }.onFailure {
            Log.w(TAG, "写入失败 $key", it)
        }.getOrNull()
    }

    /**
     * 按 LRU 淘汰到 [limitBytes] 以下。
     *
     * @param keep 刚写入的文件 —— 即使它单独就超限也不删
     *             （否则用户刚解完的歌立刻被删掉，下次又得等 42 秒）
     */
    fun evictIfNeeded(context: Context, limitBytes: Long, keep: File? = null) {
        val files = listCached(context)
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) return

        val candidates = files
            .filter { it.absolutePath != keep?.absolutePath }
            .sortedBy { it.lastModified() }

        var removed = 0
        for (f in candidates) {
            if (total <= limitBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                removed++
                Log.i(TAG, "淘汰 ${f.name}（${"%.1f".format(len / 1_048_576.0)}MB）")
            }
        }
        Log.i(
            TAG,
            "淘汰完成：删除 $removed 个，现用 ${"%.1f".format(total / 1_048_576.0)}MB / " +
                "上限 ${limitBytes / 1_048_576.0}MB",
        )
    }

    /** 列出所有缓存文件。 */
    fun listCached(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(EXT) }?.toList() ?: emptyList()

    /** 当前占用的总字节。 */
    fun usedBytes(context: Context): Long = listCached(context).sumOf { it.length() }

    /** 清空全部缓存（设置页按钮）。@return 删除的文件数 */
    fun clear(context: Context): Int {
        val files = listCached(context)
        var n = 0
        for (f in files) if (f.delete()) n++
        Log.i(TAG, "清空缓存：删除 $n 个文件")
        return n
    }
}
