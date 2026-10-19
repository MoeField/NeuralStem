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
 * ★G-3 重构原型：验证「完整曲长 WAV 随机写」的技术前提。
 *
 * ## 背景
 * 用户在实测中发现「边分离边播」有三个缺陷：
 * 1. 分离完会跳回起始点（因为落盘文件只含 [startFrame, 曲尾)）；
 * 2. 结果不包含 startFrame 之前的部分；
 * 3. 两轨波形差一截（波形是全曲提取，stem 只有后半段）。
 *
 * 提出的方案：**按块写入正确位置**（完整曲长文件，随机写），
 * 播放侧判断「该位置是否已有数据」。
 *
 * ## 本测试验证什么（纯 JVM，不依赖设备）
 * - Q1：能否预分配完整长度 + 按绝对帧位置随机写？
 * - Q3：能否边写边读（同一文件，读写各自 fd）？
 *
 * Q2（ExoPlayer 读半静音 WAV）已在真机验证通过，不需要单测。
 */
class RandomWriteProtoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sr = 44_100
    private val ch = 2
    private val frameBytes = ch * 2
    private val headerSize = 44

    /** 生成一段可辨识的样本（正弦波，便于核对位置）。 */
    private fun tone(frames: Int, freq: Double = 440.0): FloatArray {
        val out = FloatArray(frames * ch)
        for (i in 0 until frames) {
            val v = (0.5 * sin(2.0 * Math.PI * freq * i / sr)).toFloat()
            out[i * ch] = v
            out[i * ch + 1] = v
        }
        return out
    }

    private fun writeHeader(raf: RandomAccessFile, totalFrames: Int) {
        val dataSize = totalFrames * frameBytes
        raf.seek(0)
        raf.write("RIFF".toByteArray(Charsets.US_ASCII))
        raf.writeIntLE(36 + dataSize)
        raf.write("WAVE".toByteArray(Charsets.US_ASCII))
        raf.write("fmt ".toByteArray(Charsets.US_ASCII))
        raf.writeIntLE(16)
        raf.writeShortLE(1)
        raf.writeShortLE(ch)
        raf.writeIntLE(sr)
        raf.writeIntLE(sr * frameBytes)
        raf.writeShortLE(frameBytes)
        raf.writeShortLE(16)
        raf.write("data".toByteArray(Charsets.US_ASCII))
        raf.writeIntLE(dataSize)
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(
            byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
            ),
        )
    }

    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
    }

    private fun toPcm16(samples: FloatArray): ByteArray {
        val b = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            b[i++] = (v and 0xFF).toByte()
            b[i++] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    // ==================== Q1：预分配 + 随机写 ====================

    @Test
    fun preallocateThenRandomWriteProducesValidFullLengthWav() {
        val totalFrames = sr * 10 // 10 秒
        val f = File(tmp.root, "rw.wav")

        RandomAccessFile(f, "rw").use { raf ->
            // 1) 预分配完整长度（后段是 0 填充）
            raf.setLength(headerSize + totalFrames.toLong() * frameBytes)
            writeHeader(raf, totalFrames)

            // 2) 随机写：只写 [5s, 7s) 这一段（模拟“分离到中间某段”）
            val startFrame = sr * 5
            val frames = sr * 2
            val data = toPcm16(tone(frames))
            raf.seek(headerSize + startFrame.toLong() * frameBytes)
            raf.write(data)
        }

        // 3) 校验文件结构
        assertEquals(headerSize + totalFrames.toLong() * frameBytes, f.length())

        RandomAccessFile(f, "r").use { raf ->
            val hdr = ByteArray(headerSize)
            raf.readFully(hdr)
            assertEquals("RIFF", String(hdr, 0, 4, Charsets.US_ASCII))
            assertEquals("data", String(hdr, 36, 4, Charsets.US_ASCII))

            // 4) 校验 [0, 5s) 是 0（未写区域）
            raf.seek(headerSize.toLong())
            val before = ByteArray(1000)
            raf.readFully(before)
            assertTrue("未写区域应全为 0", before.all { it == 0.toByte() })

            // 5) 校验 [5s, 7s) 有数据
            raf.seek(headerSize + sr.toLong() * 5 * frameBytes)
            val mid = ByteArray(1000)
            raf.readFully(mid)
            assertTrue("已写区域应有非零样本", mid.any { it != 0.toByte() })
        }
    }

    @Test
    fun randomWriteOutOfOrderStillCorrect() {
        val totalFrames = sr * 10
        val f = File(tmp.root, "outoforder.wav")

        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(headerSize + totalFrames.toLong() * frameBytes)
            writeHeader(raf, totalFrames)

            // 先写后段 [8s, 9s)，再写前段 [0, 1s) —— 验证乱序写入不会互相覆盖
            fun writeAt(sec: Int) {
                val data = toPcm16(tone(sr))
                raf.seek(headerSize + sec.toLong() * sr * frameBytes)
                raf.write(data)
            }
            writeAt(8)
            writeAt(0)
        }

        RandomAccessFile(f, "r").use { raf ->
            for (sec in listOf(0, 8)) {
                raf.seek(headerSize + sec.toLong() * sr * frameBytes)
                val buf = ByteArray(1000)
                raf.readFully(buf)
                assertTrue("第 ${sec}s 处应有数据", buf.any { it != 0.toByte() })
            }
            // 中间仍是 0
            raf.seek(headerSize + sr.toLong() * 4 * frameBytes)
            val mid = ByteArray(1000)
            raf.readFully(mid)
            assertTrue("未写区域应全为 0", mid.all { it == 0.toByte() })
        }
    }

    // ==================== Q3：边写边读 ====================

    @Test
    fun canReadWhileWritingSameFile() {
        val totalFrames = sr * 10
        val f = File(tmp.root, "concurrent.wav")

        val writer = RandomAccessFile(f, "rw")
        try {
            writer.setLength(headerSize + totalFrames.toLong() * frameBytes)
            writeHeader(writer, totalFrames)
            writer.fd.sync() // 让头部立即可见

            // 写第一段 [0, 1s)
            val seg = sr
            writer.seek(headerSize.toLong())
            writer.write(toPcm16(tone(seg)))

            // 用**另一个 fd** 读（模拟播放器）
            RandomAccessFile(f, "r").use { reader ->
                reader.seek(headerSize.toLong())
                val buf = ByteArray(1000)
                reader.readFully(buf)
                assertTrue("写入后应能立即读到（sync 后）", buf.any { it != 0.toByte() })
            }

            // 再写第二段 [1s, 2s)，再读
            writer.seek(headerSize + seg.toLong() * frameBytes)
            writer.write(toPcm16(tone(seg)))

            RandomAccessFile(f, "r").use { reader ->
                reader.seek(headerSize + seg.toLong() * frameBytes)
                val buf = ByteArray(1000)
                reader.readFully(buf)
                assertTrue("第二段写入后应能读到", buf.any { it != 0.toByte() })
            }
        } finally {
            writer.close()
        }
    }

    @Test
    fun readingUnwrittenRegionReturnsZerosNotError() {
        val totalFrames = sr * 10
        val f = File(tmp.root, "zeros.wav")

        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(headerSize + totalFrames.toLong() * frameBytes)
            writeHeader(raf, totalFrames)
        }

        // 未写任何数据 → 读到的应全是 0，且不越界、不报错
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(headerSize + sr.toLong() * 3 * frameBytes)
            val buf = ByteArray(4096)
            val n = raf.read(buf)
            assertEquals("应能读到（预分配区）", 4096, n)
            assertTrue("未写区域应全 0（静音，非错误）", buf.all { it == 0.toByte() })
        }
    }
}
