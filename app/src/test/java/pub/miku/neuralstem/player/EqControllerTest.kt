package pub.miku.neuralstem.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 M3.5 / T1 — `EqController` 的**多轨同步**语义单测。
 *
 * ## 为什么需要
 * M3-P3 把 EQ 搬进 `PlaybackService`，多轨时每条 stem 各挂一份 `AudioEq`。
 * 最关键的风险是【参数必须同步】：若各挂各的、参数不同步 → 两轨频响不一致
 * （听感像"跑调"，同坑 35/37 一族）。
 *
 * 真机拿不到 PCM 样本，故无法离线验证"声音变了"；但可以**确定性地**验证
 * 「写入是否真的广播到了全部轨」——这是正确性的必要条件。
 *
 * ## 怎么做到的
 * `EqController` 现在接受一个 `BandEq` 工厂（默认 = 真实 `AudioEq`）。
 * 单测注入 [FakeBandEq]（记录所有调用），即可断言广播/同步行为。
 */
class EqControllerTest {

    /** 记录调用的假 BandEq。 */
    private class FakeBandEq(
        private val bands: Int = 5,
        override val available: Boolean = true,
    ) : BandEq {
        override val bandCount: Int get() = bands
        override val bandFreqsHz: IntArray = IntArray(bands) { 60 * (it + 1) }
        override val minLevelMb: Short = -1500
        override val maxLevelMb: Short = 1500

        val levels = ShortArray(bands)
        var enabled: Boolean? = null
        var resetCalled = false
        var released = false

        override fun setBandLevel(band: Int, levelMb: Short) {
            if (band in 0 until bands) levels[band] = levelMb
        }
        override fun getBandLevel(band: Int): Short = levels.getOrElse(band) { 0 }
        override fun setEnabled(on: Boolean) { enabled = on }
        override fun reset() { levels.fill(0); resetCalled = true }
        override fun release() { released = true }
    }

    /** 记录创建的 fake 实例，用于断言“每轨一份”。 */
    private class FakeFactory(private val bands: Int = 5) {
        val created = mutableListOf<FakeBandEq>()
        fun new(): (Int) -> BandEq = { _ ->
            FakeBandEq(bands).also { created += it }
        }
    }

    @Test
    fun `setBandLevels 广播到全部轨`() {
        val f = FakeFactory()
        val c = EqController(f.new())
        c.attach("vocals", 101)
        c.attach("accompaniment", 102)
        assertEquals("两轨应各挂一份", 2, c.attachedCount)

        c.setBandLevels(shortArrayOf(600, 600, -600, 600, 600))

        assertEquals("vocals 未同步", listOf<Short>(600, 600, -600, 600, 600), f.created[0].levels.toList())
        assertEquals("accompaniment 未同步", listOf<Short>(600, 600, -600, 600, 600), f.created[1].levels.toList())
    }

    @Test
    fun `新挂载的轨立即同步到当前曲线`() {
        val f = FakeFactory()
        val c = EqController(f.new())
        c.attach("vocals", 101)                       // 第一条
        c.setBandLevels(shortArrayOf(100, 200, 300, 400, 500))
        c.attach("accompaniment", 102)                // 后挂的轨

        assertArrayEquals(
            "后挂的轨应立刻拿到当前曲线（否则两轨不一致）",
            shortArrayOf(100, 200, 300, 400, 500),
            f.created[1].levels,
        )
    }

    @Test
    fun `setEnabled 广播到全部轨`() {
        val f = FakeFactory()
        val c = EqController(f.new())
        c.attach("vocals", 101)
        c.attach("accompaniment", 102)
        c.setEnabled(true)
        assertEquals(true, f.created[0].enabled)
        assertEquals(true, f.created[1].enabled)
        assertTrue(c.isEnabled())
    }

    @Test
    fun `新挂载的轨继承当前开关状态`() {
        val f = FakeFactory()
        val c = EqController(f.new())
        c.attach("vocals", 101)
        c.setEnabled(true)
        c.attach("accompaniment", 102)               // 后挂时应立即 setEnabled(true)
        assertEquals("后挂的轨应继承开关", true, f.created[1].enabled)
    }

    @Test
    fun `setBandLevel 改单段时其余段保持不变`() {
        val f = FakeFactory()
        val c = EqController(f.new())
        c.attach("vocals", 101)
        c.setBandLevels(shortArrayOf(0, 0, 0, 0, 0))
        c.setBandLevel(2, -600)
        assertEquals(listOf<Short>(0, 0, -600, 0, 0), f.created[0].levels.toList())
    }

    @Test
    fun `reset 将曲线归零并广播`() {
        val f = FakeFactory()
        val c = EqController(f.new())
        c.attach("vocals", 101)
        c.attach("accompaniment", 102)
        c.setBandLevels(shortArrayOf(600, 600, -600, 600, 600))
        c.reset()
        assertArrayEquals(ShortArray(5), f.created[0].levels)
        assertArrayEquals(ShortArray(5), f.created[1].levels)
        assertArrayEquals("targetLevels 也应归零", ShortArray(5), c.targetLevels)
    }

    @Test
    fun `readBackLevels 反映首条轨的实际值`() {
        val f = FakeFactory()
        val c = EqController(f.new())
        c.attach("vocals", 101)
        c.setBandLevels(shortArrayOf(111, 222, 333, 444, 555))
        assertArrayEquals(
            listOf(111, 222, 333, 444, 555).map { it.toShort() }.toShortArray(),
            c.readBackLevels(),
        )
    }

    @Test
    fun `detach 释放对应轨且不再收广播`() {
        val f = FakeFactory()
        val c = EqController(f.new())
        c.attach("vocals", 101)
        c.attach("accompaniment", 102)
        c.detach("accompaniment")
        assertTrue("被 detach 的轨应被 release", f.created[1].released)
        assertEquals(1, c.attachedCount)
        // 之后广播不应到达已 detach 的轨
        val before = f.created[1].levels.copyOf()
        c.setBandLevels(shortArrayOf(1, 2, 3, 4, 5))
        assertArrayEquals("已 detach 的轨不应再收广播", before, f.created[1].levels)
    }

    @Test
    fun `detachAll 释放全部`() {
        val f = FakeFactory()
        val c = EqController(f.new())
        c.attach("a", 1); c.attach("b", 2); c.attach("c", 3)
        c.detachAll()
        assertEquals(0, c.attachedCount)
        assertTrue(f.created.all { it.released })
    }

    @Test
    fun `重复 attach 同一 id 会先释放旧的`() {
        val f = FakeFactory()
        val c = EqController(f.new())
        c.attach("vocals", 101)
        val first = f.created[0]
        c.attach("vocals", 999)                       // 重新挂
        assertTrue("旧的应被释放", first.released)
        assertEquals("仍只有一条", 1, c.attachedCount)
        assertEquals("应创建新实例", 2, f.created.size)
    }

    @Test
    fun `硬件不可用时 available 为 false 且不写入`() {
        val f = FakeFactory()
        // 构造一个 available=false 的工厂（模拟硬件不支持）
        val c = EqController { _ -> FakeBandEq(bands = 0, available = false) }
        c.attach("vocals", 101)
        assertFalse(c.available)
        assertEquals(0, c.bandCount)
        // 不应抛异常
        c.setBandLevels(shortArrayOf(1, 2, 3))
        c.setEnabled(true)
    }

    @Test
    fun `未挂载时各操作不抛异常`() {
        val c = EqController(FakeFactory().new())
        c.setBandLevels(shortArrayOf(1, 2, 3))       // 无轨
        c.setEnabled(true)
        c.reset()
        c.detach("nope")
        assertEquals(0, c.attachedCount)
        assertEquals(0, c.readBackLevels().size)
    }
}
