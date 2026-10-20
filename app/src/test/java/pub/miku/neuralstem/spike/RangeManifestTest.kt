package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★边播两阶段：区间 manifest 的纯逻辑单测（不碰文件系统）。
 *
 * 背景：stem 文件是**预分配到完整曲长**的，未写区全是 0 →
 * `File.length()` 无法判断"分离到哪了"。断进程后只能靠 manifest 恢复。
 */
class RangeManifestTest {

    private fun mf(
        total: Int = 100,
        ranges: List<IntRange> = emptyList(),
        edgeStart: Int = 0,
        complete: Boolean = false,
    ) = RangeManifest(
        totalFrames = total,
        sampleRate = 44_100,
        ranges = ranges,
        edgeStartFrame = edgeStart,
        complete = complete,
    )

    // ---------------- mergeRanges ----------------

    @Test
    fun `adjacent ranges merge into one`() {
        // [0,10) 与 [10,20) 相邻 → [0,20)
        val merged = RangeManifest.mergeRanges(listOf(0..9, 10..19))
        assertEquals(listOf(0..19), merged)
    }

    @Test
    fun `overlapping ranges merge`() {
        val merged = RangeManifest.mergeRanges(listOf(0..9, 5..19, 15..29))
        assertEquals(listOf(0..29), merged)
    }

    @Test
    fun `disjoint ranges stay separate`() {
        val merged = RangeManifest.mergeRanges(listOf(0..9, 20..29))
        assertEquals(listOf(0..9, 20..29), merged)
    }

    @Test
    fun `unsorted input is sorted and merged`() {
        val merged = RangeManifest.mergeRanges(listOf(50..59, 0..9, 10..19))
        assertEquals(listOf(0..19, 50..59), merged)
    }

    @Test
    fun `empty input yields empty`() {
        assertTrue(RangeManifest.mergeRanges(emptyList()).isEmpty())
    }

    @Test
    fun `invalid ranges are dropped`() {
        // last < first 的区间应被丢弃
        val merged = RangeManifest.mergeRanges(listOf(10..5, 0..9))
        assertEquals(listOf(0..9), merged)
    }

    // ---------------- plus / complete ----------------

    @Test
    fun `plus appends and merges`() {
        val m = mf(ranges = listOf(0..9)).plus(10, 20)
        assertEquals(listOf(0..19), m.ranges)
    }

    @Test
    fun `covering the whole length marks complete`() {
        val m = mf(total = 100, ranges = listOf(0..49)).plus(50, 100)
        assertTrue("应覆盖 [0,100) → complete", m.complete)
        assertEquals(listOf(0..99), m.ranges)
    }

    @Test
    fun `partial coverage is not complete`() {
        val m = mf(total = 100, ranges = listOf(0..49)).plus(60, 100)
        assertFalse("中间有洞 → 不该 complete", m.complete)
    }

    @Test
    fun `two-phase scenario forward then backfill`() {
        // 用户在 40 帧处点边播 → 先算 [40,100)，再补 [0,40)
        var m = mf(total = 100, edgeStart = 40)
        m = m.plus(40, 100)
        assertFalse("只有后半 → 不完整", m.complete)
        assertEquals(listOf(40..99), m.ranges)
        assertFalse("0 帧此时不可用", m.contains(0))
        assertTrue("40 帧已可用", m.contains(40))

        m = m.plus(0, 40)
        assertTrue("补完前半 → 完整", m.complete)
        assertEquals(listOf(0..99), m.ranges)
        assertTrue("0 帧现在可用", m.contains(0))
    }

    // ---------------- missingRanges ----------------

    @Test
    fun `empty manifest misses everything`() {
        assertEquals(listOf(0..99), mf(total = 100).missingRanges())
    }

    @Test
    fun `complete manifest misses nothing`() {
        assertTrue(mf(total = 100, ranges = listOf(0..99), complete = true).missingRanges().isEmpty())
    }

    @Test
    fun `partial manifest reports the head gap`() {
        // 只有 [40,100) → 缺 [0,40)
        assertEquals(listOf(0..39), mf(total = 100, ranges = listOf(40..99)).missingRanges())
    }

    @Test
    fun `middle hole is located`() {
        val m = mf(total = 100, ranges = listOf(0..9, 50..99))
        assertEquals(listOf(10..49), m.missingRanges())
    }

    @Test
    fun `multiple holes are reported`() {
        val m = mf(total = 100, ranges = listOf(10..19, 50..59))
        assertEquals(listOf(0..9, 20..49, 60..99), m.missingRanges())
    }

    // ---------------- coveredFraction ----------------

    @Test
    fun `covered fraction math`() {
        assertEquals(0f, mf(total = 100).coveredFraction, 0.001f)
        assertEquals(0.5f, mf(total = 100, ranges = listOf(0..49)).coveredFraction, 0.001f)
        assertEquals(1f, mf(total = 100, ranges = listOf(0..99)).coveredFraction, 0.001f)
    }

    @Test
    fun `zero total does not crash`() {
        assertEquals(0f, mf(total = 0, ranges = emptyList()).coveredFraction, 0.001f)
    }
}
