package pub.miku.neuralstem.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import pub.miku.neuralstem.spike.StemPcmRingBuffer

/**
 * ★G-3：[StemPcmDataSource] 测试（**仪器化**，需真机/模拟器）。
 *
 * 为何放 androidTest：`DataSpec` 需要 `android.net.Uri`，而 `Uri` 是 Android 框架类；
 * JVM 单测（`isReturnDefaultValues=true`）下 `Uri.parse` 返回 null → `DataSpec.build()` 抛
 * “uri must be set”。故放到仪器化环境跑。
 *
 * 验证：合成 WAV 头正确；PCM 数据正确；read==EOF；从非 0 position 打开能定位到对应帧。
 */
@RunWith(AndroidJUnit4::class)
class StemPcmDataSourceTest {

    private val uri = Uri.parse("stem://test")
    private val sampleRate = 44_100
    private val channels = 1     // 单声道便于验算：每帧 2 字节

    private fun makeBuffer(frames: IntArray): StemPcmRingBuffer {
        val rb = StemPcmRingBuffer(channels = channels, capacityFrames = 1024)
        val f = FloatArray(frames.size) { i -> frames[i] / 32768f }
        rb.append(f)
        rb.close()
        return rb
    }

    private fun readAll(ds: DataSource): ByteArray {
        val out = ArrayList<Byte>()
        val tmp = ByteArray(4096)
        while (true) {
            val n = ds.read(tmp, 0, tmp.size)
            if (n == C.RESULT_END_OF_INPUT || n < 0 || n == 0) break
            for (i in 0 until n) out.add(tmp[i])
        }
        return out.toByteArray()
    }

    @Test
    fun wavHeaderIsValidPcm16Riff() {
        val rb = makeBuffer(intArrayOf(0, 0))
        val ds = StemPcmDataSource.Factory(rb, sampleRate, channels).createDataSource()
        ds.open(DataSpec.Builder().setUri(uri).build())
        val hdr = ByteArray(StemPcmDataSource.WAV_HEADER_SIZE)
        val n = ds.read(hdr, 0, StemPcmDataSource.WAV_HEADER_SIZE)
        assertEquals(StemPcmDataSource.WAV_HEADER_SIZE, n)
        assertEquals("RIFF", String(hdr, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(hdr, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(hdr, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(hdr, 36, 4, Charsets.US_ASCII))
        assertEquals(1, hdr[20].toInt())  // PCM
        assertEquals(1, hdr[22].toInt())  // channels
        val sr = (hdr[24].toInt() and 0xFF) or ((hdr[25].toInt() and 0xFF) shl 8) or
            ((hdr[26].toInt() and 0xFF) shl 16) or ((hdr[27].toInt() and 0xFF) shl 24)
        assertEquals(sampleRate, sr)
        ds.close()
    }

    @Test
    fun pcmSamplesAfterHeaderAreCorrect() {
        val rb = makeBuffer(intArrayOf(1000, -1000, 0))
        val ds = StemPcmDataSource.Factory(rb, sampleRate, channels).createDataSource()
        ds.open(DataSpec.Builder().setUri(uri).build())
        val all = readAll(ds)
        ds.close()
        val pcm = all.copyOfRange(StemPcmDataSource.WAV_HEADER_SIZE, all.size)
        assertEquals(6, pcm.size)   // 3 帧 × 2 字节
        assertEquals(1000, readS16(pcm, 0))
        assertEquals(-1000, readS16(pcm, 2))
        assertEquals(0, readS16(pcm, 4))
    }

    @Test
    fun openAtNonZeroPositionSeeksToFrame() {
        val rb = makeBuffer(IntArray(10) { it * 100 })
        val ds = StemPcmDataSource.Factory(rb, sampleRate, channels).createDataSource()
        // 头(44) + 跳过前 4 帧(4×2=8) → 从第 5 个元素下标 4 开始（值 4*100=400）
        val startPos = StemPcmDataSource.WAV_HEADER_SIZE + 4L * 2
        ds.open(DataSpec.Builder().setUri(uri).setPosition(startPos).build())
        val tmp = ByteArray(2)
        val n = ds.read(tmp, 0, 2)
        ds.close()
        assertEquals(2, n)
        assertEquals(4 * 100, readS16(tmp, 0))
    }

    @Test
    fun readPastEndReturnsEof() {
        val rb = makeBuffer(intArrayOf(1, 2))
        val ds = StemPcmDataSource.Factory(rb, sampleRate, channels).createDataSource()
        ds.open(DataSpec.Builder().setUri(uri).build())
        val all = readAll(ds)
        assertEquals(StemPcmDataSource.WAV_HEADER_SIZE + 2 * 2, all.size)
        val tmp = ByteArray(8)
        assertEquals(C.RESULT_END_OF_INPUT, ds.read(tmp, 0, 8))
        ds.close()
    }

    private fun readS16(b: ByteArray, off: Int): Int {
        val lo = b[off].toInt() and 0xFF
        val hi = b[off + 1].toInt()
        return (hi shl 8) or lo
    }
}
