package pub.miku.neuralstem.spike

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ★G-3-重构：[RandomAccessWavWriter] 单测。
 *
 * 锁死「完整曲长 + 按绝对帧随机写 + 未写区为静音」的行为，
 * 防止后续改回“顺序追加”或“close 时回填 size”。
 */
class RandomAccessWavWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sr = 8_000 // 用小采样率让文件小、测试快
    private val ch = 2
    private val frameBytes = ch * 2
    private val headerSize = 44

    private fun tone(frames: Int, amp: Float = 0.5f): FloatArray {
        val out = FloatArray(frames * ch)
        for (i in 0 until frames) {
            val v = (amp * sin(2.0 * Math.PI * 440.0 * i / sr)).toFloat()
            out[i * ch] = v
            out[i * ch + 1] = v
        }
        return out
    }

    private fun readS16At(f: File, absFrame: Int): Int {
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(headerSize + absFrame.toLong() * frameBytes)
            val lo = raf.read()
            val hi = raf.read()
            return (hi shl 8) or (lo and 0xFF)
        }
    }

    /**
     * 区间 [fromFrame, fromFrame+frames) 内是否存在非零样本。
     *
     * 为何不单点判：正弦波在 phase=0 处样本恰好为 0（sin(0)=0），
     * 单点断言会误报。判“区间内有声”更稳健。
     */
    private fun hasSoundIn(f: File, fromFrame: Int, frames: Int = 64): Boolean {
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(headerSize + fromFrame.toLong() * frameBytes)
            val buf = ByteArray(frames * frameBytes)
            raf.readFully(buf)
            return buf.any { it != 0.toByte() }
        }
    }

    private fun readHeaderInt(f: File, off: Int): Int {
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(off.toLong())
            val b = ByteArray(4)
            raf.readFully(b)
            return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
        }
    }

    @Test
    fun preallocatesFullLengthAndWritesHeaderImmediately() {
        val total = sr * 4
        val f = File(tmp.root, "a.wav")
        RandomAccessWavWriter(f, ch, sr, total).use { /* 只构造并关闭 */ }

        assertEquals("文件长度 = 44 + total*frameBytes",
            headerSize + total.toLong() * frameBytes, f.length())
        assertEquals("data size 应为完整长度（不是 0）", total * frameBytes, readHeaderInt(f, 40))
        assertEquals("RIFF size = 36 + data", 36 + total * frameBytes, readHeaderInt(f, 4))

        RandomAccessFile(f, "r").use { raf ->
            val hdr = ByteArray(headerSize)
            raf.readFully(hdr)
            assertEquals("RIFF", String(hdr, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(hdr, 8, 4, Charsets.US_ASCII))
            assertEquals("data", String(hdr, 36, 4, Charsets.US_ASCII))
        }
    }

    @Test
    fun unwrittenRegionIsSilenceNotError() {
        val total = sr * 4
        val f = File(tmp.root, "b.wav")
        RandomAccessWavWriter(f, ch, sr, total).use { }

        // 完全没写过 → 任意位置读到 0（静音）
        for (frame in listOf(0, 100, sr * 2, total - 1)) {
            assertEquals("未写区 frame=$frame 应为 0", 0, readS16At(f, frame))
        }
    }

    @Test
    fun writeAtPutsSamplesAtAbsolutePosition() {
        val total = sr * 4
        val f = File(tmp.root, "c.wav")
        val startAbs = sr        // 从第 1 秒开始写 0.5 秒
        val frames = sr / 2
        RandomAccessWavWriter(f, ch, sr, total).use { w ->
            w.writeAt(tone(frames), 0, frames, startAbs)
            assertEquals("水位线 = 写入末端", startAbs + frames, w.writtenUpToFrame)
            w.flush()
        }

        // 写入区应有非零样本
        assertTrue("startAbs 附近应已写入", hasSoundIn(f, startAbs))
        assertTrue("写入区中部应非零", hasSoundIn(f, startAbs + frames / 4))
        // 之前与之后仍是静音
        assertEquals("startAbs 之前应为静音", 0, readS16At(f, startAbs - 10))
        assertEquals("写入末端之后应为静音", 0, readS16At(f, startAbs + frames + 10))
    }

    @Test
    fun outOfOrderWritesDoNotOverwriteEachOther() {
        val total = sr * 4
        val f = File(tmp.root, "d.wav")
        val frames = sr / 4
        RandomAccessWavWriter(f, ch, sr, total).use { w ->
            // 先写第 3 秒，再写第 0 秒（乱序）
            w.writeAt(tone(frames), 0, frames, sr * 3)
            w.writeAt(tone(frames), 0, frames, 0)
            w.flush()
        }
        assertTrue("第 3 秒应仍有数据", hasSoundIn(f, sr * 3))
        assertTrue("第 0 秒应已有数据", hasSoundIn(f, 0))
        assertEquals("中间未写区应为静音", 0, readS16At(f, sr))
    }

    @Test
    fun waterMarkTracksMaxWrittenEnd() {
        val total = sr * 4
        val f = File(tmp.root, "e.wav")
        val frames = sr / 4
        RandomAccessWavWriter(f, ch, sr, total).use { w ->
            assertEquals(0, w.writtenUpToFrame)
            w.writeAt(tone(frames), 0, frames, sr)          // 写到 1s+0.25s
            assertEquals(sr + frames, w.writtenUpToFrame)
            w.writeAt(tone(frames), 0, frames, sr * 2)      // 更靠后 → 水位线前移
            assertEquals(sr * 2 + frames, w.writtenUpToFrame)
            w.writeAt(tone(frames), 0, frames, 0)           // 更靠前 → 水位线不变
            assertEquals("乱序写不应降低水位线", sr * 2 + frames, w.writtenUpToFrame)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeBeyondTotalFramesIsRejected() {
        val total = sr
        val f = File(tmp.root, "f.wav")
        RandomAccessWavWriter(f, ch, sr, total).use { w ->
            w.writeAt(tone(sr / 2), 0, sr / 2, sr * 3 / 4) // 越界
        }
    }

    @Test
    fun closeIsIdempotent() {
        val f = File(tmp.root, "g.wav")
        val w = RandomAccessWavWriter(f, ch, sr, sr)
        w.close()
        w.close() // 不应抛异常
    }
}
