package pub.miku.neuralstem.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs

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
    /** ★⑭c：头部字节数（magic4+ver4+sr4+frames8+n4 = 24）。用于文件大小自洽校验。 */
    private const val HEADER_BYTES = 24L

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
        return fileForStamp(context, uri.toString(), stamp)
    }

    /**
     * ★按「稳定 key + stamp」定位缓存文件（2026-10-26）。
     *
     * ## 为何需要字符串 key
     * `ncm://<key>` 不是真实文件，[stampFor] 取不到 size/mtime → 永远不缓存。
     * 而 NCM 的波形提取正是走这条路径（见 `AudioSource.RawBytes`）。
     * 所以给它一个不依赖文件系统的入口。
     */
    private fun fileForStamp(context: Context, key: String, stamp: String): File {
        val h = (key + "|" + stamp).hashCode().toUInt().toString(16)
        return File(dir(context), "wf_$h.bin")
    }

    /**
     * 按字符串 key 取缓存。
     *
     * @param key 稳定标识（如 `ncm://5fda704`）
     * @param stamp 内容指纹（如字节长度）；内容变了则失效
     */
    fun load(context: Context, key: String, stamp: String): WaveformData? =
        loadFile(fileForStamp(context, key, stamp))

    /** 按字符串 key 存缓存。 */
    fun save(context: Context, key: String, stamp: String, wf: WaveformData) {
        saveFile(fileForStamp(context, key, stamp), wf)
    }

    fun load(context: Context, uri: Uri): WaveformData? {
        val f = cacheFile(context, uri) ?: return null
        return loadFile(f)
    }

    private fun loadFile(f: File): WaveformData? {
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { ins ->
                require(ins.readInt() == MAGIC)
                require(ins.readInt() == VERSION)
                val sr = ins.readInt()
                val frames = ins.readLong()
                val n = ins.readInt()
                require(n in 1..10_000_000)
                // ★⑭c：文件大小自洽校验（24B 头 + n×8B）；不一致 = 损坏/半截 → 视为 miss。
                require(f.length() == HEADER_BYTES + n.toLong() * 8L) {
                    "corrupt cache: size=${f.length()} expected=${HEADER_BYTES + n * 8}"
                }
                val min = FloatArray(n) { ins.readFloat() }
                val max = FloatArray(n) { ins.readFloat() }
                WaveformData(min, max, sr, frames)
            }
        }.onFailure { Log.w(TAG, "load failed", it) }.getOrNull()
    }

    fun save(context: Context, uri: Uri, wf: WaveformData) {
        val f = cacheFile(context, uri) ?: return
        saveFile(f, wf)
    }

    private fun saveFile(f: File, wf: WaveformData) {
        // ★⑭c：写入前自洽校验——pointCount 应与 (totalFrames / (sr/50)) 相符（±1），
        //   否则视为“半截”包络，**拒绝写入**（防坏缓存污染）。
        if (!isSelfConsistent(wf)) {
            Log.w(TAG, "refuse saving inconsistent waveform: pts=${wf.pointCount} frames=${wf.totalFrames} sr=${wf.sampleRate}")
            return
        }
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

    /** ★⑭c：包络自洽（点数 ≈ 时长×50）。容忍 ±1.5 个点（取整误差）。 */
    internal fun isSelfConsistent(wf: WaveformData): Boolean {
        if (wf.sampleRate <= 0 || wf.totalFrames <= 0 || wf.pointCount <= 0) return false
        val expect = wf.totalFrames.toDouble() * 50.0 / wf.sampleRate
        return abs(expect - wf.pointCount) <= 1.5
    }

    /** ★⑭c：清除损坏/半截的旧缓存（文件大小与头部不自洽者）。启动时调一次。 */
    fun cleanCorrupt(context: Context) {
        val d = dir(context)
        d.listFiles()?.forEach { f ->
            runCatching {
                DataInputStream(f.inputStream().buffered()).use { ins ->
                    if (ins.readInt() != MAGIC || ins.readInt() != VERSION) {
                        f.delete(); return@runCatching
                    }
                    ins.readInt(); ins.readLong(); val n = ins.readInt()
                    if (n <= 0 || f.length() != HEADER_BYTES + n.toLong() * 8L) {
                        Log.w(TAG, "cleanCorrupt: ${f.name} (n=$n size=${f.length()})")
                        f.delete()
                    }
                }
            }.onFailure { runCatching { f.delete() } }
        }
    }
}
