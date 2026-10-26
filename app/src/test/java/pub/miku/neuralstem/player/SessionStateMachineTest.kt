package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ★ 状态机骨架（`SessionStateMachine`）的 JVM 单测。
 *
 * 覆盖 4 条不变量：
 * - **I1**：换队列前先退多轨（否则两首同时响）。
 * - **I2**：EQ 由“会话就绪”派生（`Unsupported` ⟺ 无有效会话）。
 * - **I3**：分离 `Done` **一次性消费**（遗留态不触发）。
 * - 派生量：`separationOn` / `isMultiTrack` / `eqAvailable`。
 */
class SessionStateMachineTest {

    /** 记录副作用的假实现。 */
    private class FakeEffects : SessionStateMachine.Effects {
        var closeFollowersCalls = 0
        val attachEqCalls = mutableListOf<Pair<List<Int>, Boolean>>()
        var detachEqCalls = 0

        override fun closeFollowers() { closeFollowersCalls++ }
        override fun attachEq(sessionIds: List<Int>, enabled: Boolean) {
            attachEqCalls += sessionIds to enabled
        }
        override fun detachEq() { detachEqCalls++ }
    }

    private fun newSm() = FakeEffects().let { it to SessionStateMachine(it) }

    // ---------------- I1 ----------------

    @Test
    fun `I1 多轨时换队列会先退出多轨`() {
        val (fx, sm) = newSm()
        sm.enterMultiTrack("primary", listOf("vocals", "accomp"), listOf(101, 102))
        assertTrue(sm.isMultiTrack)

        sm.beforeSetQueue()

        assertFalse("换队列前应退出多轨", sm.isMultiTrack)
        assertEquals("应关掉跟随轨一次", 1, fx.closeFollowersCalls)
    }

    @Test
    fun `I1 单轨时换队列不做任何事`() {
        val (fx, sm) = newSm()
        sm.beforeSetQueue()
        assertFalse(sm.isMultiTrack)
        assertEquals(0, fx.closeFollowersCalls)
    }

    // ---------------- I2 ----------------

    @Test
    fun `I2 无有效会话时 EQ 为 Unsupported 并卸载`() {
        val (fx, sm) = newSm()
        sm.onSessionsReady(listOf(0))   // sessionId==0 = 未建立
        assertFalse(sm.eqAvailable)
        assertTrue(sm.eq is EqState.Unsupported)
        assertEquals("应调用 detachEq", 1, fx.detachEqCalls)
    }

    @Test
    fun `I2 会话就绪后 EQ 挂载且可用`() {
        val (fx, sm) = newSm()
        sm.onSessionsReady(listOf(1921))
        assertTrue(sm.eqAvailable)
        assertEquals(listOf(1921), (sm.eq as EqState.Attached).sessionIds)
        assertEquals(listOf(listOf(1921) to false), fx.attachEqCalls)
    }

    @Test
    fun `I2 开关 EQ 会以同一会话重挂并带 enabled`() {
        val (fx, sm) = newSm()
        sm.onSessionsReady(listOf(500))
        sm.setEqEnabled(true)
        assertTrue(sm.eqEnabled)
        assertEquals(listOf(500) to true, fx.attachEqCalls.last())
    }

    @Test
    fun `I2 无会话时 setEqEnabled 被忽略`() {
        val (fx, sm) = newSm()
        sm.onSessionsReady(listOf(0))   // Unsupported
        sm.setEqEnabled(true)
        assertFalse(sm.eqEnabled)
        assertEquals(0, fx.attachEqCalls.size)
    }

    @Test
    fun `I2 enterMultiTrack 会把 enabled 状态带到新会话`() {
        val (fx, sm) = newSm()
        sm.onSessionsReady(listOf(1))
        sm.setEqEnabled(true)
        sm.enterMultiTrack("p", listOf("a", "b"), listOf(11, 12))
        assertTrue("多轨会话 EQ 应保持开启", sm.eqEnabled)
        assertEquals(listOf(11, 12) to true, fx.attachEqCalls.last())
    }

    // ---------------- I3 ----------------

    @Test
    fun `I3 分离 Done 只能被消费一次`() {
        val (_, sm) = newSm()
        sm.startSeparation()
        val done = SeparationTask.Done2(
            vocalsFile = File("v.wav"),
            accompanimentFile = File("a.wav"),
            modelLoadMs = 0,
            inferTimeMs = 1,
            peakPssMb = 2f,
            audioDurationMs = 3,
            fromCache = true,
        )
        sm.onSeparationDone(done)

        val first = sm.consumeSeparationDone()
        assertEquals(done, first)
        assertTrue("消费后应回到 Idle", sm.separation is SeparationTask.Idle)

        assertNull("再次消费应为 null（遗留态不会触发）", sm.consumeSeparationDone())
    }

    @Test
    fun `I3 4-stem Done4 同样可被一次性消费`() {
        val (_, sm) = newSm()
        sm.startSeparation()
        val done = SeparationTask.Done4(
            stemFiles = listOf(File("v.wav"), File("d.wav")),
            inferTimeMs = 1, peakPssMb = 2f, audioDurationMs = 3,
        )
        sm.onSeparationDone(done)
        assertEquals(done, sm.consumeSeparationDone())
        assertNull(sm.consumeSeparationDone())
    }

    @Test
    fun `I3 Preparing 不是 Done（消费为 null）`() {
        val (_, sm) = newSm()
        sm.onSeparationPreparing()
        assertTrue(sm.separation is SeparationTask.Preparing)
        assertNull(sm.consumeSeparationDone())
    }

    @Test
    fun `I3 Cancelled 不是 Done 且不残留 Done`() {
        val (_, sm) = newSm()
        sm.startSeparation()
        sm.onSeparationCancelled()
        assertTrue(sm.separation is SeparationTask.Cancelled)
        assertNull(sm.consumeSeparationDone())
    }

    @Test
    fun `I3 无 Done 时消费返回 null`() {
        val (_, sm) = newSm()
        sm.startSeparation()
        sm.onSeparationProgress(50)
        assertNull(sm.consumeSeparationDone())
    }

    @Test
    fun `I3 失败态不产生 Done`() {
        val (_, sm) = newSm()
        sm.startSeparation()
        sm.onSeparationFailed("boom")
        assertNull(sm.consumeSeparationDone())
        assertTrue(sm.separation is SeparationTask.Failed)
    }

    // ---------------- 派生量 ----------------

    @Test
    fun `派生量 separationOn 跟随多轨`() {
        val (_, sm) = newSm()
        assertFalse(sm.separationOn)
        sm.enterMultiTrack("p", listOf("a"), listOf(1))
        assertTrue(sm.separationOn)
        sm.exitMultiTrack()
        assertFalse(sm.separationOn)
    }

    @Test
    fun `exitMultiTrack 在单轨时是幂等的`() {
        val (fx, sm) = newSm()
        sm.exitMultiTrack()
        assertEquals(0, fx.closeFollowersCalls)
    }

    // ---------------- R-a：播放队列 ----------------

    /** 构造队列项（uri=title=名字）。 */
    private fun qt(vararg names: String) = names.map { QueueTrack(it, it) }

    @Test
    fun `队列：setQueue 起始下标越界则钳到合法区间，空列表置 Empty`() {
        val (_, sm) = newSm()
        val abc = qt("a", "b", "c")
        sm.setQueue(abc, startIndex = 9)
        assertEquals("越界 → 钳到末位", QueueState.Active(abc, 2), sm.queue)
        sm.setQueue(abc, startIndex = -5)
        assertEquals("负数 → 钳到 0", QueueState.Active(abc, 0), sm.queue)
        sm.setQueue(emptyList())
        assertTrue(sm.queue is QueueState.Empty)
    }

    @Test
    fun `队列：advance 到尾部返回 false 且不越界`() {
        val (_, sm) = newSm()
        sm.setQueue(qt("a", "b"), 0)
        assertTrue(sm.advanceQueue())
        assertEquals(1, (sm.queue as QueueState.Active).currentIndex)
        assertFalse("已在尾部 → false", sm.advanceQueue())
        assertEquals(1, (sm.queue as QueueState.Active).currentIndex)
    }

    @Test
    fun `队列：retreat 到头部返回 false`() {
        val (_, sm) = newSm()
        sm.setQueue(qt("a", "b"), 1)
        assertTrue(sm.retreatQueue())
        assertEquals(0, (sm.queue as QueueState.Active).currentIndex)
        assertFalse(sm.retreatQueue())
    }

    @Test
    fun `队列：wrapQueueToStart 回开头（循环歌单用）`() {
        val (_, sm) = newSm()
        sm.setQueue(qt("a", "b", "c"), 2)
        assertTrue(sm.wrapQueueToStart())
        assertEquals(0, (sm.queue as QueueState.Active).currentIndex)
    }

    @Test
    fun `队列：removeFromQueue 索引自动维持（删当前 → 指向下一个）`() {
        val (_, sm) = newSm()
        sm.setQueue(qt("a", "b", "c"), 1)
        sm.removeFromQueue("b")   // 删当前曲
        val q = sm.queue as QueueState.Active
        assertEquals(listOf("a", "c"), q.tracks.map { it.uri })
        assertEquals("删当前 → 下标不变（指向下一个）", 1, q.currentIndex)
        assertEquals("c", q.current?.uri)
    }

    @Test
    fun `队列：removeFromQueue 删更前的曲则下标前移`() {
        val (_, sm) = newSm()
        sm.setQueue(qt("a", "b", "c"), 2)
        sm.removeFromQueue("a")
        val q = sm.queue as QueueState.Active
        assertEquals(listOf("b", "c"), q.tracks.map { it.uri })
        assertEquals(1, q.currentIndex)
        assertEquals("c", q.current?.uri)
    }

    @Test
    fun `队列：清空后回归 Empty`() {
        val (_, sm) = newSm()
        sm.setQueue(qt("a"), 0)
        sm.clearQueue()
        assertTrue(sm.queue is QueueState.Empty)
    }

    // ==================== ★AVS3 解码会话 ====================

    /*
     * 这一组测的是「切歌时旧解码怎么办」的决策逻辑。
     * 决策错了会有两种后果，都很坑：
     *  - 太早放弃 → 已烧的 CPU 白费，下次还得再等 31 秒
     *  - 该放弃时不放弃 → 新歌白等一个注定要被丢弃的任务
     */

    @Test
    fun `AVS3 初始为 Idle`() {
        val (_, sm) = newSm()
        assertTrue(sm.avs3Decode is Avs3Decode.Idle)
    }

    @Test
    fun `AVS3 进度低于阈值时切歌 → 立即取消（不要求收尾）`() {
        val (_, sm) = newSm()
        sm.beginAvs3Decode("a", "A")
        sm.onAvs3Progress("a", 50)

        // 50% < 80% → 让它直接死，不要收尾
        assertFalse(sm.requestAvs3Decode())
        assertTrue(sm.avs3Decode is Avs3Decode.Idle)
    }

    @Test
    fun `AVS3 进度达到阈值时切歌 → 要求旧任务收尾`() {
        val (_, sm) = newSm()
        sm.beginAvs3Decode("a", "A")
        sm.onAvs3Progress("a", 85)

        // 85% >= 80% → 让它跑完，能落缓存
        assertTrue(sm.requestAvs3Decode())
        val st = sm.avs3Decode
        assertTrue(st is Avs3Decode.Running)
        assertEquals(true, (st as Avs3Decode.Running).finishing)
    }

    @Test
    fun `AVS3 刚好等于阈值也算收尾（边界）`() {
        val (_, sm) = newSm()
        sm.beginAvs3Decode("a", "A")
        sm.onAvs3Progress("a", SessionStateMachine.AVS3_FINISH_THRESHOLD)
        assertTrue(sm.requestAvs3Decode())
    }

    @Test
    fun `AVS3 已在收尾时再切歌 → 不再重复要求收尾`() {
        val (_, sm) = newSm()
        sm.beginAvs3Decode("a", "A")
        sm.onAvs3Progress("a", 90)
        assertTrue(sm.requestAvs3Decode())      // 第一次：要求收尾

        // 用户又切了一首。旧任务已在 finishing，不能再“收尾”一次。
        assertFalse(sm.requestAvs3Decode())
        assertTrue(sm.avs3Decode is Avs3Decode.Idle)
    }

    @Test
    fun `AVS3 无任务时切歌 → 无需收尾`() {
        val (_, sm) = newSm()
        assertFalse(sm.requestAvs3Decode())
    }

    @Test
    fun `AVS3 陈旧进度回调被丢弃（目标已换）`() {
        val (_, sm) = newSm()
        sm.beginAvs3Decode("a", "A")
        // 目标已切到 b
        sm.beginAvs3Decode("b", "B")
        // a 的迟到回调不应污染 b 的进度
        sm.onAvs3Progress("a", 99)
        val st = sm.avs3Decode as Avs3Decode.Running
        assertEquals("b", st.uri)
        assertEquals(0, st.percent)
    }

    @Test
    fun `AVS3 陈旧完成回调不会把新任务置为 Idle`() {
        val (_, sm) = newSm()
        sm.beginAvs3Decode("a", "A")
        sm.beginAvs3Decode("b", "B")
        sm.onAvs3Done("a")   // a 解完了（但它已经不是当前目标）
        assertTrue(sm.avs3Decode is Avs3Decode.Running)
        assertEquals("b", (sm.avs3Decode as Avs3Decode.Running).uri)
    }

    @Test
    fun `AVS3 完成后回到 Idle`() {
        val (_, sm) = newSm()
        sm.beginAvs3Decode("a", "A")
        sm.onAvs3Progress("a", 100)
        sm.onAvs3Done("a")
        assertTrue(sm.avs3Decode is Avs3Decode.Idle)
    }

    @Test
    fun `AVS3 进度被钳在 0 到 100`() {
        val (_, sm) = newSm()
        sm.beginAvs3Decode("a", "A")
        sm.onAvs3Progress("a", 500)
        assertEquals(100, (sm.avs3Decode as Avs3Decode.Running).percent)
        sm.onAvs3Progress("a", -20)
        assertEquals(0, (sm.avs3Decode as Avs3Decode.Running).percent)
    }

    @Test
    fun `AVS3 失败后可清除`() {
        val (_, sm) = newSm()
        sm.beginAvs3Decode("a", "A")
        sm.onAvs3Failed("a", "解码器不可用")
        assertTrue(sm.avs3Decode is Avs3Decode.Failed)
        sm.clearAvs3Fail()
        assertTrue(sm.avs3Decode is Avs3Decode.Idle)
    }

    @Test
    fun `AVS3 isDecodingFor 只对当前目标为真`() {
        val (_, sm) = newSm()
        sm.beginAvs3Decode("a", "A")
        assertTrue(sm.isDecodingFor("a"))
        assertFalse(sm.isDecodingFor("b"))
    }

    @Test
    fun `AVS3 解码不影响其他状态（多轨与队列独立）`() {
        val (_, sm) = newSm()
        sm.enterMultiTrack("p", listOf("p", "f"), listOf(1, 2))
        sm.beginAvs3Decode("a", "A")
        sm.onAvs3Progress("a", 50)
        // 解码是独立任务，不该把多轨踢回单轨
        assertTrue(sm.isMultiTrack)
        sm.cancelAvs3Decode()
        assertTrue(sm.isMultiTrack)
    }
}
