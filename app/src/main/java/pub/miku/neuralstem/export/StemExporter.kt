package pub.miku.neuralstem.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 阶段 UI / U7：**导出分离结果**。
 *
 * ## 规格（用户决策）
 * - 目标：`Music/separate/<歌曲名>/`
 * - **WAV**（无损优先）；**含原曲**；**英文文件名**
 *   （`vocals.wav` / `drums.wav` / `bass.wav` / `other.wav` / `original.wav`）
 * - **不带 EQ / 音量处理** —— 导出的是**纯净**分离产物。
 *
 * ## 实现
 * - API 29+：用 **MediaStore**（`Audio`）写入 `Music/separate/...`
 *   （无需存储权限；`RELATIVE_PATH` 指定子目录）。
 * - API < 29：回退到 `Environment.getExternalStoragePublicDirectory(MUSIC)`
 *   （需 WRITE_EXTERNAL_STORAGE，minSdk=23 时仍可能遇到）。
 *
 * 注：本类只做**拷贝**（分离产物已是 WAV），不做转码。
 */
object StemExporter {

    private const val TAG = "StemExporter"

    /** stem 顺序 → 英文文件名（与 [pub.miku.neuralstem.spike.Spleeter4Dsp.STEM_KINDS] 对应）。 */
    private val STEM_NAMES = mapOf(
        "vocals" to "vocals.wav",
        "drums" to "drums.wav",
        "bass" to "bass.wav",
        "other" to "other.wav",
    )

    /**
     * @param songName 歌曲名（用作子目录名）
     * @param stemFiles 分离产物（WAV）；按文件名/STEM_KINDS 推断 stem 名
     * @param original 原曲（可选，作为 `original.wav`/原扩展名导出）
     * @return 人类可读的结果消息
     */
    fun export(
        context: Context,
        songName: String,
        stemFiles: List<File>,
        original: File? = null,
    ): String {
        val safeSong = songName.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "song" }
        val dirRel = "${Environment.DIRECTORY_MUSIC}/separate/$safeSong"
        var ok = 0
        val errors = ArrayList<String>()

        stemFiles.forEach { f ->
            val outName = STEM_NAMES[f.name.lowercase()] ?: englishName(f.name)
            if (copyTo(context, f, dirRel, outName)) ok++ else errors += f.name
        }
        original?.let { o ->
            // 原曲：文件名用原名（英文清洗）+ 原扩展名
            val ext = o.extension.ifBlank { "wav" }
            if (copyTo(context, o, dirRel, "original.$ext")) ok++ else errors += o.name
        }

        val msg = buildString {
            append("导出 $ok 个文件 → Music/separate/$safeSong/")
            if (errors.isNotEmpty()) append("；失败: ${errors.joinToString()}")
        }
        Log.i(TAG, msg)
        return msg
    }

    /** 拷贝 [src] 到 Music 下的 [dirRel]/[outName]。返回是否成功。 */
    private fun copyTo(context: Context, src: File, dirRel: String, outName: String): Boolean {
        if (!src.exists()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                copyViaMediaStore(context, src, dirRel, outName)
            } else {
                copyViaPublicDir(src, dirRel, outName)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "copyTo($outName) failed", t)
            false
        }
    }

    private fun copyViaMediaStore(context: Context, src: File, dirRel: String, outName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, outName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, dirRel)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: return false
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    @Suppress("DEPRECATION")
    private fun copyViaPublicDir(src: File, dirRel: String, outName: String): Boolean {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val dir = File(root, dirRel.substringAfter("Music/"))
        if (!dir.exists() && !dir.mkdirs()) return false
        val out = File(dir, outName)
        src.inputStream().use { ins -> out.outputStream().use { ins.copyTo(it) } }
        return true
    }

    /** 非已知 stem 的文件名 → 英文清洗（保底）。 */
    private fun englishName(name: String): String {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "wav")
        val safe = base.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "stem" }
        return "$safe.$ext"
    }
}
