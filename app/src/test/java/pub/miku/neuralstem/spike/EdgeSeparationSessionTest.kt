package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★G-3：[EdgeSeparationSession] 状态机单测。
 *
 * 覆盖：begin 建 buffers、首段就绪、进度、完成（EOF）、失败、abort 清理、重复 begin。
 */
class EdgeSeparationSessionTest {

    private fun fresh(): EdgeSeparationSession = EdgeSeparationSession.get()

    @Test
    fun `begin 建立两个 buffer 并进入 Preparing`() {
        val s = fresh()
        s.abort()                       // 清干净
        s.begin(startFrame = 1000, sampleRate = 44_100, channels = 2, capacityFrames = 1024)
        assertTrue(s.state.value is EdgeSeparationSession.State.Preparing)
        assertNotNull("vocals buffer 应存在", s.vocalsBuffer)
        assertNotNull("accomp buffer 应存在", s.accompBuffer)
        assertEquals(1000, s.startFrame)
        assertEquals(2, s.channels)
        assertEquals(44_100, s.sampleRate)
        s.abort()
    }

    @Test
    fun `首段就绪只在 Preparing 时触发一次`() {
        val s = fresh()
        s.begin(0, 44_100, 2, 1024)
        assertTrue(!s.firstSegmentReady)
        s.markFirstSegmentReady(10)
        assertTrue(s.firstSegmentReady)
        assertTrue(s.state.value is EdgeSeparationSession.State.FirstSegmentReady)
        // 再次调用不应回退状态
        s.markFirstSegmentReady(20)
        assertTrue(s.state.value is EdgeSeparationSession.State.FirstSegmentReady)
        s.abort()
    }

    @Test
    fun `进度更新 首段后进入 Running`() {
        val s = fresh()
        s.begin(0, 44_100, 2, 1024)
        s.updateProgress(5)             // Preparing → FirstSegmentReady
        assertTrue(s.state.value is EdgeSeparationSession.State.FirstSegmentReady)
        s.updateProgress(50)            // → Running
        val st = s.state.value
        assertTrue(st is EdgeSeparationSession.State.Running)
        assertEquals(50, (st as EdgeSeparationSession.State.Running).percent)
        s.abort()
    }

    @Test
    fun `完成关闭 buffer 读端返回 EOF`() {
        val s = fresh()
        s.begin(0, 44_100, 2, 1024)
        val rb = s.vocalsBuffer!!
        rb.append(FloatArray(4) { 1f })   // 2 帧 stereo
        s.complete()
        assertTrue(s.state.value is EdgeSeparationSession.State.Done)
        assertTrue("buffer 应已关闭", rb.isClosed)
        // 读到 EOF
        val cur = rb.openCursor()
        val dst = FloatArray(8)
        assertEquals(2, cur.read(dst))
        assertEquals(-1, cur.read(dst))
        cur.close()
        s.abort()
    }

    @Test
    fun `失败进入 Failed 并关闭 buffer`() {
        val s = fresh()
        s.begin(0, 44_100, 2, 1024)
        val rb = s.accompBuffer!!
        s.fail("boom")
        val st = s.state.value
        assertTrue(st is EdgeSeparationSession.State.Failed)
        assertEquals("boom", (st as EdgeSeparationSession.State.Failed).message)
        assertTrue(rb.isClosed)
        s.abort()
    }

    @Test
    fun `abort 清空 buffer 引用`() {
        val s = fresh()
        s.begin(0, 44_100, 2, 1024)
        s.abort()
        assertNull(s.vocalsBuffer)
        assertNull(s.accompBuffer)
        assertTrue(s.state.value is EdgeSeparationSession.State.Cancelled)
    }

    @Test
    fun `重复 begin 会先中止旧会话`() {
        val s = fresh()
        s.begin(0, 44_100, 2, 1024)
        val old = s.vocalsBuffer!!
        s.begin(500, 44_100, 1, 2048)
        assertTrue("旧 buffer 应被关闭", old.isClosed)
        assertEquals(500, s.startFrame)
        assertEquals(1, s.channels)
        s.abort()
    }
}
