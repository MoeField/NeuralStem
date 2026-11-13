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
 * - 目标：`Music/separate/<歌曲名>/<模型>/`（模型目录来自 stem cache 的 engine 后缀）
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
        // 4 轨（Spleeter4 / Demucs）
        "vocals" to "vocals.wav",
        "drums" to "drums.wav",
        "bass" to "bass.wav",
        "other" to "other.wav",
        // 2 轨（Spleeter 2-stem / UVR）：产物就叫 vocals.wav + accompaniment.wav。
        // 注：不写这行也会走 [englishName] 兜底得到同名，但那是**巧合**，
        //     依赖兜底逻辑的实现细节；显式写出来才不会被未来的清洗规则变更静静破坏。
        "accompaniment" to "accompaniment.wav",
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
        modelName: String? = null,
    ): String {
        val safeSong = safeDirName(songName)
        val safeModel = modelName
            ?.takeIf { it.isNotBlank() }
            ?.let(::safeDirName)
        val dirRel = "${Environment.DIRECTORY_MUSIC}/${exportRelativeDir(songName, modelName)}"
        var ok = 0
        val errors = ArrayList<String>()

        stemFiles.forEach { f ->
            val outName = outNameFor(f.name)
            if (copyTo(context, f, dirRel, outName)) ok++ else errors += f.name
        }
        original?.let { o ->
            // 原曲：文件名用原名（英文清洗）+ 原扩展名
            val ext = o.extension.ifBlank { "wav" }
            if (copyTo(context, o, dirRel, "original.$ext")) ok++ else errors += o.name
        }

        val msg = buildString {
            append("导出 $ok 个文件 → Music/separate/$safeSong/")
            if (safeModel != null) append("$safeModel/")
            if (errors.isNotEmpty()) append("；失败: ${errors.joinToString()}")
        }
        Log.i(TAG, msg)
        return msg
    }

    /** Music 下的相对目录（便于 JVM 测命名；不含 Environment 前缀）。 */
    internal fun exportRelativeDir(songName: String, modelName: String?): String {
        val safeSong = safeDirName(songName)
        val safeModel = modelName
            ?.takeIf { it.isNotBlank() }
            ?.let(::safeDirName)
        return if (safeModel != null) "separate/$safeSong/$safeModel" else "separate/$safeSong"
    }

    /**
     * 从 stem cache 目录推断模型名，作为导出子目录。
     * 目录形如 `<hash>-spleeter`、`<hash>-spleeter4`、`<hash>-demucs`、
     * `<hash>-<engine>-full`；不依赖 UI 当前选项，换曲/重建后仍可追踪。
     */
    internal fun inferModelDirName(stemFiles: List<File>): String? {
        val dir = stemFiles.firstOrNull()?.parentFile?.name ?: return null
        val base = dir.removeSuffix("-full")
        val tag = base.substringAfterLast('-', "")
        if (tag.isBlank()) return null
        return when (tag) {
            "spleeter" -> "spleeter-2stem"
            "spleeter4" -> "spleeter-4stem"
            "demucs" -> "demucs"
            else -> tag
        }
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
        val resolver = context.contentResolver
        // ★B6：同名覆盖（用户默认选择）：先删旧行，避免 MediaStore 自动生成 (1).wav。
        //   只删本应用可见的同目录同名媒体；失败不影响后续 insert。
        deleteExisting(resolver, dirRel, outName)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, outName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, dirRel)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        try {
            val out = resolver.openOutputStream(uri)
                ?: run {
                    // ★B6：拿不到输出流 → 必须删掉 pending 行，否则隐藏残留。
                    runCatching { resolver.delete(uri, null, null) }
                    return false
                }
            out.use { o -> src.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return true
        } catch (t: Throwable) {
            // ★B6：拷贝异常 → 删 pending 行后交给外层记录失败。
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /** 删除同 `RELATIVE_PATH + DISPLAY_NAME` 的旧导出（同名覆盖）。 */
    private fun deleteExisting(
        resolver: android.content.ContentResolver,
        dirRel: String,
        outName: String,
    ) {
        runCatching {
            val sel = "${MediaStore.Audio.Media.RELATIVE_PATH}=? AND " +
                "${MediaStore.Audio.Media.DISPLAY_NAME}=?"
            // MediaStore 的 RELATIVE_PATH 通常带结尾 '/'；两者都试一次。
            val args = arrayOf("$dirRel/", outName)
            val ids = ArrayList<Long>()
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                sel,
                args,
                null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                while (c.moveToNext()) ids += c.getLong(idCol)
            }
            ids.forEach { id ->
                runCatching {
                    resolver.delete(
                        android.content.ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id,
                        ),
                        null,
                        null,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "deleteExisting($outName) failed", it) }
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

    /**
     * 推导导出文件名。
     *
     * ## ★ 2026-11-01 修了一个死代码 bug
     * 旧实现是 `STEM_NAMES[f.name.lowercase()]` —— 但 `STEM_NAMES` 的**键是不带
     * 扩展名的 stem 名**（`"vocals"`），而 `f.name` 是 `"vocals.wav"`。
     * 两者永远不可能相等 ⇒ **整张表从未命中过**，所有文件都走了 [englishName] 兜底。
     *
     * 它一直“看起来是对的”，因为兜底凑巧把 `vocals.wav` 原样返回了。
     * 但兜底保留了原始大小写：`VOCALS.WAV` → `VOCALS.WAV`，与预期的
     * 小写规范名不一致。
     *
     * 现改为：**按 stem 名（去扩展名）查表**，查不到再兜底。
     * （回归测试：[pub.miku.neuralstem.export.StemExportNamingTest]）
     */
    internal fun outNameFor(fileName: String): String {
        val stem = fileName.substringBeforeLast('.').lowercase()
        return STEM_NAMES[stem] ?: englishName(fileName)
    }

    /**
     * 把歌曲名变成**安全的目录名**。
     *
     * ## ★ 2026-11-01 真机修正
     * 旧实现是 `replace(Regex("[^A-Za-z0-9_-]"), "_")` —— 把所有非 ASCII 都变下划线。
     * 实测效果：`colate ななひら - Good Night.mp3` → `colate______-_Good_Night_mp3`
     * （中文/日文全变下划线，且**扩展名也进了目录名**）。
     *
     * ## 为何当时写错了
     * 那段逻辑原本用于 **stem 文件名**（确实需要纯 ASCII，见 [englishName]），
     * 被直接复用到**歌曲目录名**上。但两者要求不同：
     * - stem 文件名：规范化的英文（算法/测试依赖它们）
     * - 歌曲目录名：只是给人看的，**中文日文在 FAT/exFAT 上完全合法**
     *
     * ## 现在的做法
     * 只剔除**真正非法**的字符（`/ \ : * ? " < > |` 与控制字符），
     * 保留 Unicode 与空格；另外去掉扩展名（目录名不该带 `.mp3`）。
     */
    internal fun safeDirName(songName: String): String {
        val base = songName.substringBeforeLast('.', songName)
        val cleaned = base
            .replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_")
            .trim()
            .trimEnd('.')          // Windows/FAT 不允许目录名以点结尾
            .ifBlank { "song" }
        // 避免过长（FAT 单段上限 255；留余量给后续可能的子文件）
        return if (cleaned.length <= 80) cleaned else cleaned.take(80).trimEnd()
    }

    /** 非已知 stem 的文件名 → 英文清洗（保底）。 */
    private fun englishName(name: String): String {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "wav").lowercase()
        val safe = base.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "stem" }
        return "$safe.$ext"
    }
}
