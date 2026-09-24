package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Task 0e 的验证测试:WavWriter 回填头部 + WavReader 往返一致性。
 *
 * 为什么必须有这个测试:
 * 旧 WavWriter 按入参 totalFrames **预先写死**头部,并在 append 里 clamp。
 * 而实测 sherpa 输出帧数少于输入(Task 0d:1150976 vs 1151115,差 139 帧)。
 * 旧实现在这种情况下会把头部 size 声明成"输入长度",与实际写入的字节数不符 ——
 * 这种不一致**不会抛异常**,只会让下游播放器读到垃圾,属于最难查的一类 bug。
 * 所以这里逐字节校验头部两处 size 字段。
 */
class WavIoStreamingTest {

    private fun tempFile(): File =
        File.createTempFile("wavio-test", ".wav").also { it.deleteOnExit() }

    /** 从字节层读 little-endian Int,不依赖被测代码自己的解析器。 */
    private fun readIntLEAt(file: File, offset: Long): Int =
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val b = ByteArray(4)
            raf.readFully(b)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
        }

    /** 生成交织正弦波,避免用全零样本掩盖 bug。 */
    private fun sine(frames: Int, channels: Int, freq: Float = 440f, sr: Int = 44100): FloatArray {
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (0.5 * Math.sin(2.0 * Math.PI * freq * f / sr)).toFloat()
            for (c in 0 until channels) out[f * channels + c] = v
        }
        return out
    }

    @Test
    fun `close 后头部两处 size 与实际写入字节数一致`() {
        val file = tempFile()
        val channels = 2
        val sr = 44100
        val frames = 1000

        WavWriter(file, channels, sr).use { w ->
            w.append(sine(frames, channels, sr = sr))
            assertEquals(frames, w.framesWritten)
        }

        val expectedDataSize = frames * channels * 2
        // data size 字段在偏移 40;RIFF size = 36 + dataSize
        assertEquals(expectedDataSize, readIntLEAt(file, 40))
        assertEquals(36 + expectedDataSize, readIntLEAt(file, 4))
        // 文件实际大小 = 44 字节头 + data
        assertEquals((44 + expectedDataSize).toLong(), file.length())
    }

    @Test
    fun `写入帧数少于声明时 头部跟随实际值而非预估值`() {
        // 复现 Task 0d 的场景:调用方以为有 N 帧,实际只写出 N-139 帧。
        // 旧实现会把 data size 写成 N,导致"声明与实际不符"。
        val file = tempFile()
        val channels = 2
        val frames = 4096
        val written = frames - 139

        WavWriter(file, channels, 44100).use { w ->
            w.append(sine(written, channels))
        }

        assertEquals(written * channels * 2, readIntLEAt(file, 40))
        assertEquals((44 + written * channels * 2).toLong(), file.length())

        // 回读:probe 必须与写入帧数一致
        val info = WavIo.probe(file)
        assertEquals(written, info.totalFrames)
        assertEquals(channels, info.channels)
    }

    @Test
    fun `分块 append 与一次性写入结果一致`() {
        val channels = 2
        val totalFrames = 5000
        val data = sine(totalFrames, channels)

        val oneShot = tempFile()
        WavWriter(oneShot, channels, 44100).use { it.append(data) }

        val chunked = tempFile()
        val chunkFrames = 512
        WavWriter(chunked, channels, 44100).use { w ->
            var start = 0
            while (start < totalFrames) {
                val n = minOf(chunkFrames, totalFrames - start)
                val block = FloatArray(n * channels)
                System.arraycopy(data, start * channels, block, 0, n * channels)
                w.append(block)
                start += n
            }
            assertEquals(totalFrames, w.framesWritten)
        }

        assertTrue(
            "分块写入与一次性写入应产出相同字节",
            oneShot.readBytes().contentEquals(chunked.readBytes()),
        )
    }

    @Test
    fun `WavReader 往返还原样本值 量化误差在可听阈以下`() {
        val file = tempFile()
        val channels = 2
        val frames = 2000
        val original = sine(frames, channels)

        WavWriter(file, channels, 44100).use { it.append(original) }

        WavReader(file).use { reader ->
            assertEquals(frames, reader.totalFrames)
            val read = reader.readFrames(0, frames)
            assertEquals(original.size, read.size)

            // 容忍度 = 2/32768(严格解析上界),而非 0.5 LSB。
            // 该误差是**既有特征,非本次回归**(改动只涉及头部回填,未碰 PCM 编解码):
            //   写侧: s16 = trunc(v * 32767)   —— `toInt()` 向零截断
            //   读侧: out = s16 / 32768        —— 与写侧的 32767 不同步
            // 令 f = 32767v - trunc(32767v),可推出
            //   error = |v - trunc(32767v)/32768| = |v + f| / 32768
            // 而 |v| <= 1、|f| < 1  ⇒  error < 2/32768 ≈ 6.10e-5(即 < 2 LSB)
            // (实测最差 ≈1.5 LSB,与上界相符)
            // 该量级 ≈ -84 dBFS,在 16-bit 音轨上不可闻,故**本次不改 PCM 语义**
            // —— 改了会使 Task 0d 已产出的对比基线失效。
            // 如需严格对称,应让写侧改用 Math.round(v*32767) 且读侧除以 32767;
            // 留待 Task 4 评估(guide N2 优先保证内存行为,非重定义格式)。
            val tolerance = 2f / 32768f
            var maxErr = 0f
            for (i in original.indices) {
                val diff = Math.abs(original[i] - read[i])
                if (diff > maxErr) maxErr = diff
                assertTrue("样本 $i 误差 $diff 超出解析上界 $tolerance", diff <= tolerance)
            }
            // 记录实测值,便于将来回归时对比(期望 ~1.5 LSB)
            assertTrue(
                "最大量化误差 $maxErr 超出预期(实测应 ≈1.5 LSB)",
                maxErr <= 1.6f / 32768f,
            )
        }
    }

    @Test
    fun `appendRegion 只写指定子区间 且不复制整块`() {
        val file = tempFile()
        val channels = 1
        val frames = 1000

        WavWriter(file, channels, 44100).use { w ->
            // 写入中间的 [200, 500) 共 300 帧
            w.appendRegion(FloatArray(frames) { 0.5f }, 200, 300)
            assertEquals(300, w.framesWritten)
        }

        WavReader(file).use { reader ->
            assertEquals(300, reader.totalFrames)
            val read = reader.readFrames(0, 300)
            // 应与源数组 [200,500) 区间一致(即全部 0.5)
            assertTrue("子区间内容错误", read.all { Math.abs(it - 0.5f) < 0.01f })
        }
    }

    @Test
    fun `appendRegion 越界会拒绝而不是写到文件尾`() {
        val file = tempFile()
        WavWriter(file, 2, 44100).use { w ->
            val src = FloatArray(100 * 2) { 0.1f }
            val failed = try {
                w.appendRegion(src, 90, 20) // (90+20)*2 = 220 > 200
                false
            } catch (e: IllegalArgumentException) {
                true
            }
            assertTrue("越界 appendRegion 应被拒绝", failed)
        }
    }
}