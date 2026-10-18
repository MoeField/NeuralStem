package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ★G-3：[StemPcmRingBuffer] 单测。
 *
 * 覆盖：顺序读、多读独立游标、环形回绕、读端阻塞→唤醒、close→EOF、写端背压、seek、立体声。
 *
 * ⚠️ 健壮性原则（血泪教训）：本类的 `read()` 在无数据时会**阻塞**。
 * 测试中：
 *  - 所有辅助线程设为 **daemon**（即便卡住也不拖住 JVM）；
 *  - 等待一律带**超时**（`await(2s)` / `join(3s)`），并 `finally { close() }` 保证唤醒所有等待者；
 *  - 不在无数据时无条件 `read`（先写后读，或用有限次带超时重试）。
 */
class StemPcmRingBufferTest {

    /** 硬超时：任何用例卡住 10s 就判失败（避免整个构建无限挂起）。 */
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(10)

    private fun f(vararg v: Float) = v

    private fun assertEqF(expected: FloatArray, actual: FloatArray) {
        assertEquals("长度不同", expected.size, actual.size)
        for (i in expected.indices) assertEquals("index $i", expected[i], actual[i], 0f)
    }

    @Test
    fun `顺序读回写入的样本（单声道）`() {
        val rb = StemPcmRingBuffer(channels = 1, capacityFrames = 16)
        rb.append(f(0f, 1f, 2f, 3f))
        rb.close()
        val dst = FloatArray(4)
        assertEquals(4, rb.openCursor().read(dst, 0, 4))
        assertEqF(f(0f, 1f, 2f, 3f), dst)
    }

    @Test
    fun `两个独立游标各自从头读到相同数据`() {
        val rb = StemPcmRingBuffer(channels = 1, capacityFrames = 32)
        val data = FloatArray(8) { it.toFloat() }
        rb.append(data)
        rb.close()
        val ca = rb.openCursor()
        val cb = rb.openCursor()
        val a = FloatArray(8); val b = FloatArray(8)
        assertEquals(8, ca.read(a))
        assertEquals(8, cb.read(b))   // ca 读完不能把 cb 还没读的数据回收掉
        ca.close(); cb.close()
        assertEqF(data, a)
        assertEqF(data, b)
    }

    @Test
    fun `close 后读到末尾返回 EOF`() {
        val rb = StemPcmRingBuffer(channels = 1, capacityFrames = 8)
        rb.append(f(1f, 2f))
        rb.close()
        val cur = rb.openCursor()
        val dst = FloatArray(4)
        assertEquals(2, cur.read(dst, 0, 4))
        assertEquals(-1, cur.read(dst, 0, 4))
    }

    @Test
    fun `seek 后从指定帧读`() {
        val rb = StemPcmRingBuffer(channels = 1, capacityFrames = 16)
        rb.append(FloatArray(8) { it.toFloat() })
        rb.close()
        val cur = rb.openCursor()
        cur.seekTo(5)
        val dst = FloatArray(3)
        assertEquals(3, cur.read(dst))
        assertEqF(f(5f, 6f, 7f), dst)
    }

    @Test
    fun `立体声交织正确`() {
        val rb = StemPcmRingBuffer(channels = 2, capacityFrames = 16)
        rb.append(f(1f, 2f, 3f, 4f))   // 2 帧
        rb.close()
        val dst = FloatArray(4)
        assertEquals(2, rb.openCursor().read(dst))
        assertEqF(f(1f, 2f, 3f, 4f), dst)
    }

    @Test
    fun `读端阻塞：数据稍后写入则被唤醒（daemon+超时）`() {
        val rb = StemPcmRingBuffer(channels = 1, capacityFrames = 16)
        val got = FloatArray(3)
        val readDone = CountDownLatch(1)
        try {
            Thread {
                val n = rb.openCursor().read(got, 0, 3)
                if (n == 3) readDone.countDown()
            }.apply { isDaemon = true }.start()
            Thread.sleep(150)             // 让读端先进入等待
            rb.append(f(7f, 8f, 9f))
            assertTrue("读端应在数据到来后被唤醒", readDone.await(3, TimeUnit.SECONDS))
            assertEqF(f(7f, 8f, 9f), got)
        } finally {
            rb.close()                    // 无论如何唤醒等待者
        }
    }

    @Test
    fun `环形回绕：容量小于写入总量仍正确顺序（含消费者线程）`() {
        val rb = StemPcmRingBuffer(channels = 1, capacityFrames = 4)
        val total = 10
        val out = FloatArray(total)
        try {
            val reader = Thread {
                val cur = rb.openCursor()
                var got = 0
                val deadline = System.currentTimeMillis() + 4_000
                while (got < total && System.currentTimeMillis() < deadline) {
                    val n = cur.read(out, got, total - got)
                    if (n > 0) got += n else if (n < 0) break
                }
            }.apply { isDaemon = true }
            reader.start()
            for (i in 0 until total) rb.append(f(i.toFloat()))
            reader.join(4_000)
            for (i in 0 until total) assertEquals(i.toFloat(), out[i], 0f)
        } finally {
            rb.close()
        }
    }

    @Test
    fun `写端背压：容量满时阻塞，消费后继续`() {
        val rb = StemPcmRingBuffer(channels = 1, capacityFrames = 2)
        val appended = AtomicInteger(0)
        try {
            val writer = Thread {
                for (i in 0 until 6) {
                    rb.append(f(i.toFloat()))
                    appended.incrementAndGet()
                }
            }.apply { isDaemon = true }
            writer.start()
            Thread.sleep(200)
            assertTrue("写端应被背压阻塞（<6），实际=${appended.get()}", appended.get() < 6)
            // 消费：带超时的有限循环（n==0 视为重试，绝不死循环）。
            val cur = rb.openCursor()
            val dst = FloatArray(1)
            var got = 0
            val deadline = System.currentTimeMillis() + 4_000
            while (got < 6 && System.currentTimeMillis() < deadline) {
                val n = cur.read(dst)
                if (n > 0) got += n else if (n < 0) break
            }
            writer.join(4_000)
            assertEquals("应在超时内读完 6 帧", 6, got)
            assertEquals(6, appended.get())
        } finally {
            rb.close()
        }
    }
}
