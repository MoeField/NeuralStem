package pub.miku.neuralstem.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ★「stem 文件还在不在」的测试（2026-11-01）。
 *
 * ## 对应的真实 bug（用户实报）
 * 清理缓存后，**分离开关仍然可以打开**，但打开后没有声音。
 *
 * 根因：`PlayerViewModel.lastStems` 是内存里的 `File` 引用，而
 * `SeparationRepository.clearCache()` 只删磁盘目录、**不通知 VM**。
 * 于是开关判断「lastStems 非空」→ 放行 → `loadMultiTrack` → 文件不存在 → 无声。
 *
 * ## 为什么测「过滤」而不是直接测 VM
 * `PlayerViewModel` 是 `AndroidViewModel`，JVM 里跑不了。
 * 所以把判据抽成 [StemAvailability]（纯 File.exists），这里直接测它。
 *
 * ## 这个测试真正防的是什么
 * 防**未来有人把「文件存在校验」删掉或写错**（比如只看 list 非空）。
 * 如果校验失效，第一条用例就会失败。
 */
class StemAvailabilityTest {

    private lateinit var dir: File

    private fun stem(id: String, name: String): Triple<String, String, File> =
        Triple(id, name, File(dir, name))

    /** 建一个真实存在的文件。 */
    private fun touch(name: String): File = File(dir, name).apply { writeText("RIFF") }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("stemavail").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---------------- alive() ----------------

    @Test
    fun `全部存在时原样返回`() {
        touch("vocals.wav")
        touch("accompaniment.wav")
        val stems = listOf(
            stem("vocals", "vocals.wav"),
            stem("accompaniment", "accompaniment.wav"),
        )
        assertEquals(2, StemAvailability.alive(stems).size)
    }

    @Test
    fun `缓存被清后返回空 —— 这就是“开关不该能开”的场景`() {
        // 模拟：先有文件，然后被 clearCache 删掉（lastStems 仍持有引用）
        val f1 = touch("vocals.wav")
        val f2 = touch("accompaniment.wav")
        f1.delete()
        f2.delete()

        val stems = listOf(
            stem("vocals", "vocals.wav"),
            stem("accompaniment", "accompaniment.wav"),
        )
        // ★关键断言：旧实现只看 list 非空 → 会放行；这里必须为空
        assertTrue("列表本身非空（模拟 lastStems 未清）", stems.isNotEmpty())
        assertEquals("但文件都不在了 → 必须过滤成空", 0, StemAvailability.alive(stems).size)
    }

    @Test
    fun `部分缺失时只保留存在的`() {
        touch("vocals.wav")
        // accompaniment 不创建
        val stems = listOf(
            stem("vocals", "vocals.wav"),
            stem("accompaniment", "accompaniment.wav"),
        )
        val alive = StemAvailability.alive(stems)
        assertEquals(1, alive.size)
        assertEquals("vocals", alive.first().first)
    }

    @Test
    fun `保持原有顺序`() {
        touch("b.wav")
        touch("a.wav")
        val stems = listOf(stem("b", "b.wav"), stem("a", "a.wav"))
        assertEquals(listOf("b", "a"), StemAvailability.alive(stems).map { it.first })
    }

    @Test
    fun `空列表返回空`() {
        assertEquals(0, StemAvailability.alive(emptyList()).size)
    }

    // ---------------- allAlive() ----------------

    @Test
    fun `allAlive 对 null 与空列表都是 false`() {
        assertFalse(StemAvailability.allAlive(null))
        assertFalse(StemAvailability.allAlive(emptyList()))
    }

    @Test
    fun `allAlive 要求全部存在`() {
        touch("vocals.wav")
        val partial = listOf(
            stem("vocals", "vocals.wav"),
            stem("accompaniment", "accompaniment.wav"),
        )
        assertFalse("缺一个就不是全部可用", StemAvailability.allAlive(partial))

        touch("accompaniment.wav")
        assertTrue(StemAvailability.allAlive(partial))
    }
}
