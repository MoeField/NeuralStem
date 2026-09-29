package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        sm.onSeparationDone(cached = true)

        val first = sm.consumeSeparationDone()
        assertEquals(SeparationTask.Done(cached = true), first)
        assertTrue("消费后应回到 Idle", sm.separation is SeparationTask.Idle)

        assertNull("再次消费应为 null（遗留态不会触发）", sm.consumeSeparationDone())
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
}
