package pub.miku.neuralstem.spike

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★G-1a：benchmark **编排**（[DeviceBenchmark.runBenchmarkPlan]）单测。
 *
 * 覆盖（纯逻辑，无需原生引擎）：
 * - 进度回调序列（done 从 1..total，含跳过项）
 * - 结果累积 + 每模型 onResult（半态落盘）调用
 * - **取消**：isActive 变 false → 不再开始下一个（当前结束即停）
 * - 跳过（measure 返回 null）不产生结果，但仍计入进度
 */
class DeviceBenchmarkPlanTest {

    private fun spec(id: String) = ModelSpec(
        id = id, displayName = id, fileName = "$id.onnx",
        engine = SherpaSourceSeparation.MODE_UVR, sizeMb = 1, purpose = Purpose.GENERAL,
    )

    private fun bench(id: String, rtf: Float) = DeviceBenchmark.ModelBenchmark(id, rtf, 1000L)

    @Test
    fun `进度回调序列为 1 到 total 且每模型落盘一次`() = runBlocking {
        val specs = listOf(spec("a"), spec("b"), spec("c"))
        val progress = mutableListOf<Pair<Int, String>>()
        val saved = mutableListOf<String>()

        val out = DeviceBenchmark.runBenchmarkPlan(
            pending = specs, total = specs.size,
            isActive = { true },
            measure = { s -> bench(s.id, 2f) },
            onResult = { s, _ -> saved += s.id },
            onProgress = { done, _, id -> progress += done to id },
        )

        assertEquals(listOf(1 to "a", 2 to "b", 3 to "c"), progress)
        assertEquals(listOf("a", "b", "c"), saved)
        assertEquals(setOf("a", "b", "c"), out.keys)
    }

    @Test
    fun `取消：第 1 个测完后 isActive 变 false 则只测 1 个`() = runBlocking {
        val specs = listOf(spec("a"), spec("b"), spec("c"))
        var calls = 0
        var active = true
        val progress = mutableListOf<Int>()

        val out = DeviceBenchmark.runBenchmarkPlan(
            pending = specs, total = specs.size,
            isActive = { active },
            measure = { s ->
                calls++
                if (calls >= 1) active = false   // 第 1 个测完后取消
                bench(s.id, 2f)
            },
            onProgress = { done, _, _ -> progress += done },
        )

        assertEquals("只应测 a（第 2 个开始前已取消）", listOf("a"), out.keys.toList())
        assertEquals(listOf(1), progress)
    }

    @Test
    fun `跳过项 measure 返回 null 不计入结果但仍计进度`() = runBlocking {
        val specs = listOf(spec("a"), spec("b"), spec("c"))
        val progress = mutableListOf<Int>()

        val out = DeviceBenchmark.runBenchmarkPlan(
            pending = specs, total = specs.size,
            isActive = { true },
            measure = { s -> if (s.id == "b") null else bench(s.id, 2f) },  // b 跳过
            onProgress = { done, _, _ -> progress += done },
        )

        assertEquals(setOf("a", "c"), out.keys)
        assertEquals("跳过也计入进度", listOf(1, 2, 3), progress)
    }

    @Test
    fun `空 pending 立即返回且无进度`() = runBlocking {
        val progress = mutableListOf<Int>()
        val out = DeviceBenchmark.runBenchmarkPlan(
            pending = emptyList<ModelSpec>(), total = 0,
            isActive = { true },
            measure = { s -> bench(s.id, 1f) },
            onProgress = { done, _, _ -> progress += done },
        )
        assertTrue(out.isEmpty())
        assertTrue(progress.isEmpty())
    }
}
