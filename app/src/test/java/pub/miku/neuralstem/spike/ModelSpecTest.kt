package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 M2 任务 A:模型注册表 + stem 顺序修正的回归测试。
 *
 * 重点:Inst 系模型(`Inst_HQ_3`)必须是 [StemOrder.ACCOMP_FIRST] ——
 * 阶段 M 已用互相关实证其运行时两轨对调(见 [ModelSpec] 类注释)。
 * 若有人新增 Inst 变体却忘了标,或误改了已标的,本测试会立即失败。
 */
class ModelSpecTest {

    @Test
    fun `Inst 系模型标记为 ACCOMP_FIRST 需交换两轨`() {
        val inst = ModelRegistry.byId("uvr-Inst_HQ_3")
        assertNotNull("uvr-Inst_HQ_3 应在注册表中", inst)
        assertEquals(StemOrder.ACCOMP_FIRST, inst!!.stemOrder)
        assertTrue("Inst 系必须需要交换", inst.needsStemSwap)
    }

    @Test
    fun `非 Inst 系模型保持 VOCALS_FIRST 不交换`() {
        for (id in listOf("uvr-Voc_FT", "uvr-9482", "uvr-KARA", "uvr-Main", "spleeter-2stems-fp16")) {
            val m = ModelRegistry.byId(id)
            assertNotNull("$id 应在注册表中", m)
            assertEquals("$id 应为 VOCALS_FIRST", StemOrder.VOCALS_FIRST, m!!.stemOrder)
            assertFalse("$id 不应交换", m.needsStemSwap)
        }
    }

    @Test
    fun `所有 stemOrder 为 ACCOMP_FIRST 的都是伴奏导向`() {
        // 不变式:交换两轨只对 Inst/instrumental 导向的模型成立。
        for (m in ModelRegistry.ALL) {
            if (m.stemOrder == StemOrder.ACCOMP_FIRST) {
                assertEquals(
                    "${m.id} 交换了两轨,但用途不是 INSTRUMENTAL",
                    Purpose.INSTRUMENTAL, m.purpose,
                )
            }
        }
    }

    /**
     * ★实验模型的 stem 顺序 **已 NCC 实证**（2026-10-20，BF00A）。
     *
     * 这里把那次实测的结论固定下来 —— 若有人“按名字”改回去（比如觉得
     * KARA_2 应该和人声优先），本测试会立即失败。
     *
     * 实测数据（参考 = Spleeter 2-stem，20s，判据 margin ≥ 0.10）：
     * ```
     * Inst_1/2/3, Inst_HQ_1/2/4/5, Inst_Main : same≈0.058 swap≈0.499 margin≈0.44 → ACCOMP_FIRST
     * KARA_2                                  : same=0.059 swap=0.498 margin=0.44 → ACCOMP_FIRST
     * 1_9703 / 2_9682 / 3_9662                : same=0.431 swap=0.019 margin=0.41 → VOCALS_FIRST
     * Crowd_HQ_1                              : same=0.302 swap=0.398 margin=0.096 → UNCERTAIN（保留原标）
     * ```
     */
    @Test
    fun `实验模型的 stem 顺序与 NCC 实证结果一致`() {
        val expectedAccompFirst = listOf(
            "uvr-Inst_1", "uvr-Inst_2", "uvr-Inst_3",
            "uvr-Inst_HQ_1", "uvr-Inst_HQ_2", "uvr-Inst_HQ_4", "uvr-Inst_HQ_5",
            "uvr-Inst_Main",
            // ★KARA_2 是最容易被“按名字”改错的一个 —— 实测它也是反的。
            "uvr-KARA_2",
        )
        val expectedVocalsFirst = listOf(
            "uvr-9703", "uvr-9682", "uvr-9662",
            // 证据不足，保留原标注（margin 0.096 < 0.10）
            "uvr-Crowd_HQ_1",
        )

        val byId = ModelRegistry.EXPERIMENTAL.associateBy { it.id }

        for (id in expectedAccompFirst) {
            val m = byId[id]
            assertNotNull("$id 应在 EXPERIMENTAL 中", m)
            assertEquals(
                "$id 经 NCC 实证为 ACCOMP_FIRST（same≈0.058 vs swap≈0.499）",
                StemOrder.ACCOMP_FIRST, m!!.stemOrder,
            )
        }
        for (id in expectedVocalsFirst) {
            val m = byId[id]
            assertNotNull("$id 应在 EXPERIMENTAL 中", m)
            assertEquals(
                "$id 经 NCC 实证为 VOCALS_FIRST（或证据不足保留原标）",
                StemOrder.VOCALS_FIRST, m!!.stemOrder,
            )
        }
    }

    @Test
    fun `注册表 id 唯一且都能按 id 反查`() {
        val ids = ModelRegistry.ALL.map { it.id }
        assertEquals("id 不应重复", ids.size, ids.toSet().size)
        for (m in ModelRegistry.ALL) {
            assertEquals("按 id 反查应命中自身", m, ModelRegistry.byId(m.id))
        }
    }

    @Test
    fun `未知 id 返回 null`() {
        assertNull(ModelRegistry.byId("nonexistent-model"))
    }

    @Test
    fun `Spleeter 同时提供 vocals 与 accompaniment 两个模型文件`() {
        assertEquals(
            "Spleeter 的双模型文件名都应给出",
            "accompaniment.fp16.onnx", ModelRegistry.SPLEETER.spleeterAccompFile,
        )
        assertEquals(SherpaSourceSeparation.MODE_SPLEETER, ModelRegistry.SPLEETER.engine)
    }

    @Test
    fun `UVR 变体都指向 UVR 引擎且不带 Spleeter 伴奏文件`() {
        for (m in ModelRegistry.UVR_VARIANTS) {
            assertEquals("${m.id} 应为 UVR 引擎", SherpaSourceSeparation.MODE_UVR, m.engine)
            assertNull("${m.id} 不应带 spleeterAccompFile", m.spleeterAccompFile)
        }
    }

    @Test
    fun `默认引擎为 Spleeter`() {
        assertEquals(ModelRegistry.SPLEETER, ModelRegistry.DEFAULT)
    }
}
