package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★G-2a：内置 CPU→模型对照表（[BenchmarkTable]）单测。
 *
 * 覆盖：精确命中（大小写/空格归一化）、未命中、hasExact、只含已知模型 id。
 */
class BenchmarkTableTest {

    @Test
    fun `精确命中 G100 返回已知模型`() {
        val t = BenchmarkTable.lookup("G100")
        assertTrue(t.isNotEmpty())
        assertEquals(8.54f, t["spleeter-2stems-fp16"]!!.rtf, 0.01f)
        assertEquals(1.03f, t["uvr-9482"]!!.rtf, 0.01f)
    }

    @Test
    fun `归一化：小写与空格都能命中`() {
        assertTrue(BenchmarkTable.hasExact("sm8650"))
        assertTrue(BenchmarkTable.hasExact(" SM8650 "))
        assertEquals(BenchmarkTable.lookup("SM8650").keys, BenchmarkTable.lookup("sm8650").keys)
    }

    @Test
    fun `未命中返回空`() {
        assertTrue(BenchmarkTable.lookup("UNKNOWN_CPU_XYZ").isEmpty())
        assertFalse(BenchmarkTable.hasExact("UNKNOWN_CPU_XYZ"))
    }

    @Test
    fun `表内模型 id 都在 ModelRegistry`() {
        for (cpu in BenchmarkTable.knownCpus) {
            for (id in BenchmarkTable.lookup(cpu).keys) {
                assertTrue("$id 不在 ModelRegistry", ModelRegistry.byId(id) != null)
            }
        }
    }

    @Test
    fun `模型 rtf 均为正数`() {
        for (cpu in BenchmarkTable.knownCpus) {
            BenchmarkTable.lookup(cpu).values.forEach {
                assertTrue("${it.modelId} rtf 应 >0", it.rtf > 0f)
            }
        }
    }
}
