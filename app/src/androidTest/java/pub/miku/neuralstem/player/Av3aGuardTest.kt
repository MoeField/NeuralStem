package pub.miku.neuralstem.player

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** B3/AV3A：公开版 MP4 codec 探测的仪器化验证。 */
@RunWith(AndroidJUnit4::class)
class Av3aGuardTest {

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = 8 + payload.size
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(size).array())
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun hdlr(): ByteArray {
        val p = ByteArray(12)
        "soun".toByteArray(Charsets.US_ASCII).copyInto(p, 8)
        return box("hdlr", p)
    }

    private fun mp4(format: String): ByteArray {
        val entry = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(8).put(format.toByteArray(Charsets.US_ASCII)).array()
        val stsdPayload = ByteArray(8)
        ByteBuffer.wrap(stsdPayload).order(ByteOrder.BIG_ENDIAN).putInt(4, 1)
        val stsd = box("stsd", stsdPayload + entry)
        val stbl = box("stbl", stsd)
        val minf = box("minf", stbl)
        val mdia = box("mdia", hdlr() + minf)
        val trak = box("trak", mdia)
        return box("moov", trak)
    }

    private fun write(name: String, bytes: ByteArray): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.cacheDir, name).apply { writeBytes(bytes) }
    }

    @Test
    fun av3a_m4a_被拒绝_mp4a_不被拒绝() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val av3a = write("guard_av3a.m4a", mp4("av3a"))
        val aac = write("guard_aac.m4a", mp4("mp4a"))
        assertTrue(Av3aGuard.isUnsupportedAv3a(ctx, Uri.fromFile(av3a)))
        assertFalse(Av3aGuard.isUnsupportedAv3a(ctx, Uri.fromFile(aac)))
    }
}
