package pub.miku.neuralstem.playlist

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** 用设备上的真实文件验证 AVS3 头部探测。 */
@RunWith(AndroidJUnit4::class)
class Avs3DeviceProbeTest {

    private fun headOf(path: String): ByteArray? {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(path)
        if (!f.exists()) return null
        return MediaSourceReader.readHead(ctx, Uri.fromFile(f))
    }

    @Test
    fun avs3_m4a_is_detected() {
        val head = headOf("/sdcard/Music/FromTF/祝乾亮 - 二向箔降维打击.m4a")
        Assume.assumeNotNull(head)
        assertTrue("设备上的 AVS3 m4a 应被识别", Avs3Probe.hasAvs3Codec(head!!))
    }

    @Test
    fun mp3_is_not_misdetected() {
        val head = headOf("/sdcard/Music/CytusTest/who_am_i.mp3")
        Assume.assumeNotNull(head)
        assertFalse("mp3 不应被误判为 AVS3", Avs3Probe.hasAvs3Codec(head!!))
    }
}
