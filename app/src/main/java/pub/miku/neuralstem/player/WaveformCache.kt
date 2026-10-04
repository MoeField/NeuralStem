package pub.miku.neuralstem.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 波形包络**文件缓存**（R-h / W-1，从 checkpoint 线移植）。
 *
 * ## 为何
 * 包络提取需要**全曲解码**（5 分钟歌 ≈ 20s）。反复切到同一首歌会**重复提取**。
 * 把包络落盘（~120KB/首）后，命中即可**秒出**。
 *
 * ## key
 * `uri + size + lastModified` → 稳定文件名。文件变了（size/mtime 变）则缓存失效。
 * 取不到 size（如某些流）→ 不缓存。
 *
 * ## 格式（二进制）
 * `magic(int) | version(int) | sampleRate(int) | totalFrames(long) | pointCount(int)`
 * `| min[pointCount](float×N) | max[pointCount](float×N)`
 *
 * 缓存属**非关键路径**：所有 IO 均 try/catch 包裹，失败仅记日志，绝不抛出。
 */
object WaveformCache {

    private const val TAG = "WaveformCache"
    private const val MAGIC = 0x4E535756   // "NSWV"
    private const val VERSION = 1

    private fun dir(context: Context): File =
        File(context.filesDir, "waveform-cache").apply { if (!exists()) mkdirs() }

    /** 取 (size, lastModified)；取不到返回 null（则不缓存）。 */
    private fun stampFor(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) } ?: return null
            if (!f.exists()) return null
            return "${f.length()}_${f.lastModified()}"
        }
        return runCatching {
            var size = -1L
            var mtime = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    val mi = c.getColumnIndex("last_modified")
                    if (mi >= 0 && !c.isNull(mi)) mtime = c.getLong(mi)
                }
            }
            if (size <= 0) null else "${size}_$mtime"
        }.getOrNull()
    }

    private fun cacheFile(context: Context, uri: Uri): File? {
        val stamp = stampFor(context, uri) ?: return null
        val key = (uri.toString() + "|" + stamp).hashCode().toUInt().toString(16)
        return File(dir(context), "wf_$key.bin")
    }

    fun load(context: Context, uri: Uri): WaveformData? {
        val f = cacheFile(context, uri) ?: return null
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { ins ->
                require(ins.readInt() == MAGIC)
                require(ins.readInt() == VERSION)
                val sr = ins.readInt()
                val frames = ins.readLong()
                val n = ins.readInt()
                require(n in 1..10_000_000)
                val min = FloatArray(n) { ins.readFloat() }
                val max = FloatArray(n) { ins.readFloat() }
                WaveformData(min, max, sr, frames)
            }
        }.onFailure { Log.w(TAG, "load failed", it) }.getOrNull()
    }

    fun save(context: Context, uri: Uri, wf: WaveformData) {
        val f = cacheFile(context, uri) ?: return
        runCatching {
            DataOutputStream(f.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(wf.sampleRate)
                out.writeLong(wf.totalFrames)
                out.writeInt(wf.pointCount)
                wf.min.forEach { out.writeFloat(it) }
                wf.max.forEach { out.writeFloat(it) }
            }
            Log.i(TAG, "saved ${wf.pointCount} pts → ${f.name}")
        }.onFailure { Log.w(TAG, "save failed", it) }
    }
}
