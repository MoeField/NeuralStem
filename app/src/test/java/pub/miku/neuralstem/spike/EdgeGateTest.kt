package pub.miku.neuralstem.spike

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ★闸门（2026-10-20）：边分离边播「播到哪 vs 分离到哪」的纯逻辑单测。
 *
 * ## 背景（真机实报）
 * 文件模式下的 stem 文件是**预分配的整曲长度**，未分离区是静音。
 * 旧逻辑在"首段就绪"（约 4s）就切轨 → 播 4s 后掉进静音区 → "放一小段就没声"。
 * 闸门用水位线判断"当前位置到底有没有数据"，并靠**滞环**避免 play/pause 抖动。
 *
 * ## 为什么能纯 JVM 测
 * `EdgeSeparationSession` 只依赖 `android.util.Log`（`isReturnDefaultValues=true` 下是空操作）
 * 与 `java.io.File`（这里传临时路径，不真写）。
 */
class EdgeGateTest {

    private val s = EdgeSeparationSession.get()

    /** 用 44.1k / 2ch，startFrame=0，total=600s 的假会话。 */
    private val sr = 44_100
    private val lead = 15 * sr
    private val safety = 2 * sr

    @Before
    fun setUp() {
        s.abort()
    }

    @After
    fun tearDown() {
        s.abort()
    }

    private fun beginFilesSession(startFrame: Int = 0, totalFrames: Int = 600 * sr) {
        val dir = File(System.getProperty("java.io.tmpdir"), "edgegate-test")
        dir.mkdirs()
        s.beginFiles(
            startFrame = startFrame,
            sampleRate = sr,
            channels = 2,
            totalFrames = totalFrames,
            vocalsFile = File(dir, "v_${System.nanoTime()}.wav"),
            accompFile = File(dir, "a_${System.nanoTime()}.wav"),
        )
    }

    @Test
    fun `beginFiles 后进入 Buffering，不得直接可播`() {
        beginFilesSession()
        assertEquals(EdgeSeparationSession.Gate.Buffering, s.gate.value)
    }

    @Test
    fun `水位线领先不足 LEAD 时保持 Buffering`() {
        beginFilesSession()
        s.updateWaterMark(lead - 1)
        assertEquals(EdgeSeparationSession.Gate.Buffering, s.gate.value)
    }

    @Test
    fun `水位线领先达到 LEAD 时转 Playable`() {
        beginFilesSession()
        s.updateWaterMark(lead)
        assertEquals(EdgeSeparationSession.Gate.Playable, s.gate.value)
    }

    @Test
    fun `播放追上水位线 → Starved`() {
        beginFilesSession()
        s.updateWaterMark(lead)                       // → Playable
        val avail = s.availableFrames
        s.onPlaybackTick(avail - safety)              // 距水位线刚好 SAFETY
        assertEquals(EdgeSeparationSession.Gate.Starved, s.gate.value)
    }

    @Test
    fun `Starved 后水位线只追一点点不会抖动回 Playable`() {
        beginFilesSession()
        s.updateWaterMark(lead)
        s.onPlaybackTick(s.availableFrames - safety)  // → Starved
        assertEquals(EdgeSeparationSession.Gate.Starved, s.gate.value)
        // 只前进 1s（< LEAD）→ 即使 tick 也必须仍 Starved
        s.updateWaterMark(s.writtenUpToFrame + sr)
        s.onPlaybackTick(s.availableFrames - safety)
        assertEquals(
            "前进量不足 LEAD 时不应恢复（滞环）",
            EdgeSeparationSession.Gate.Starved,
            s.gate.value,
        )
        // 再前进够 → 下一个 tick 才回 Playable
        //   ★为何要 tick：Starved→Playable 需要**播放位置**，只能由播放侧报告；
        //   分离线程不知道播到哪，不能自行推断（否则会引入双向依赖）。
        s.updateWaterMark(s.availableFrames + lead + s.startFrame)
        s.onPlaybackTick(s.availableFrames - lead)
        assertEquals(EdgeSeparationSession.Gate.Playable, s.gate.value)
    }

    @Test
    fun `分离完成后直接 Finished，且不再受水位线限制`() {
        beginFilesSession()
        s.complete()
        assertEquals(EdgeSeparationSession.Gate.Finished, s.gate.value)
        // Finished 下 tick 必须无动作
        s.onPlaybackTick(0)
        assertEquals(EdgeSeparationSession.Gate.Finished, s.gate.value)
        // Finished 下 seek 到任意位置也不应回到 Starved
        s.onSeekTo(600 * sr)
        assertEquals(EdgeSeparationSession.Gate.Finished, s.gate.value)
    }

    @Test
    fun `Buffering 期间即使完成也能到 Finished`() {
        beginFilesSession()
        // 水位线一直没动（例如极短且跑得很快）
        assertEquals(EdgeSeparationSession.Gate.Buffering, s.gate.value)
        s.complete()
        assertEquals(EdgeSeparationSession.Gate.Finished, s.gate.value)
    }

    @Test
    fun `seek 到未分离区 → Starved`() {
        beginFilesSession()
        s.updateWaterMark(lead)                      // Playable
        s.onSeekTo(s.availableFrames + safety)       // 越过水位线
        assertEquals(EdgeSeparationSession.Gate.Starved, s.gate.value)
    }

    @Test
    fun `seek 回已分离且领先够的区域 → Playable`() {
        beginFilesSession()
        s.updateWaterMark(lead * 3)
        s.onPlaybackTick(s.availableFrames - safety) // → Starved
        assertEquals(EdgeSeparationSession.Gate.Starved, s.gate.value)
        s.onSeekTo(0)                                 // 跳回起点：水位线领先 45s
        assertEquals(EdgeSeparationSession.Gate.Playable, s.gate.value)
    }

    @Test
    fun `startFrame 之后才计入 availableFrames`() {
        val start = 10 * sr
        beginFilesSession(startFrame = start)
        s.updateWaterMark(start + sr)                // 起点之后只写了 1s
        assertEquals("起点之后只有 1s → 仍不够 LEAD", 0, s.availableFrames - sr)
        assertEquals(EdgeSeparationSession.Gate.Buffering, s.gate.value)
        s.updateWaterMark(start + lead)
        assertEquals(lead, s.availableFrames)
        assertEquals(EdgeSeparationSession.Gate.Playable, s.gate.value)
    }

    @Test
    fun `availableFrames 在 startFrame 之前为 0 而不是负数`() {
        beginFilesSession(startFrame = 10 * sr)
        s.updateWaterMark(5 * sr)                    // 水位线还在起点之前
        assertEquals(0, s.availableFrames)
    }

    @Test
    fun `abort 回到 Idle`() {
        beginFilesSession()
        s.updateWaterMark(lead)
        assertEquals(EdgeSeparationSession.Gate.Playable, s.gate.value)
        s.abort()
        assertEquals(EdgeSeparationSession.Gate.Idle, s.gate.value)
        assertTrue(s.availableFrames == 0)
    }
}
