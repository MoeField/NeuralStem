package pub.miku.neuralstem.export

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P1-3：同名覆盖必须“先写新、后删旧”；失败不能丢旧导出。 */
@RunWith(AndroidJUnit4::class)
class StemExporterSafetyTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val song = "stability-export-safety"
    private val model = "test-model"

    private fun queryRows(): List<android.net.Uri> {
        val rel = "Music/separate/$song/$model/"
        val rows = ArrayList<android.net.Uri>()
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.RELATIVE_PATH}=? AND ${MediaStore.Audio.Media.DISPLAY_NAME}=?",
            arrayOf(rel, "vocals.wav"),
            null,
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            while (c.moveToNext()) {
                rows += ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(id))
            }
        }
        return rows
    }

    private fun read(uri: android.net.Uri): String =
        ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }

    @Test
    fun failure_keeps_old_export_and_overwrite_replaces_once() {
        val src = File(ctx.cacheDir, "vocals.wav").apply { writeText("OLD-CONTENT") }
        val msg1 = StemExporter.export(ctx, song, listOf(src), modelName = model)
        assertTrue(msg1, msg1.contains("导出 1 个文件"))
        val first = queryRows()
        assertEquals("同名文件应只有 1 行", 1, first.size)
        assertEquals("OLD-CONTENT", read(first.single()))

        src.writeText("NEW-CONTENT")
        val msg2 = StemExporter.export(ctx, song, listOf(src), modelName = model)
        assertTrue(msg2, msg2.contains("导出 1 个文件"))
        val second = queryRows()
        assertEquals("覆盖后仍应只有 1 行", 1, second.size)
        assertEquals("NEW-CONTENT", read(second.single()))

        src.delete()
        val msg3 = StemExporter.export(ctx, song, listOf(src), modelName = model)
        assertTrue("应报告失败", msg3.contains("失败"))
        val third = queryRows()
        assertEquals("失败后旧导出必须仍在", 1, third.size)
        assertEquals("NEW-CONTENT", read(third.single()))

        third.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
    }
}
