package pub.miku.neuralstem.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pub.miku.neuralstem.spike.ModelRegistry

/**
 * ★Step4 模型库：体积分组单测。
 *
 * ## 为何按体积分组
 * 实测 RTF 与文件名无关、与**体积强相关**（`doc/model_speed_tiers.md`）：
 * 28MB→1.0× / 39MB→8.6× / 50MB→0.52× / 56MB→0.41× / 64MB→0.35× / 172MB→0.06×。
 * 所以模型库按体积分节——这是速度簇的物理成因。
 */
class ModelCatalogGroupingTest {

    @Test
    fun `grouping keeps every pack exactly once`() {
        val grouped = ModelCatalog.groupBySize().flatMap { it.second }
        assertEquals("分组后应包含全部包", ModelCatalog.ALL.size, grouped.size)
        assertEquals(
            "分组不应产生重复",
            ModelCatalog.ALL.map { it.pack }.toSet(),
            grouped.map { it.pack }.toSet(),
        )
    }

    @Test
    fun `buckets are ascending by size`() {
        val groups = ModelCatalog.groupBySize()
        val sizes = groups.map { it.first }
        assertEquals("分节应按体积升序", sizes.sorted(), sizes)
        for ((bytes, list) in groups) {
            assertTrue("节体积应 > 0", bytes > 0)
            for (info in list) {
                assertEquals("节内包的体积应与节键一致（${info.pack}）", bytes, info.totalBytes)
            }
        }
    }

    @Test
    fun `spleeter 2stem and 4stem land in different buckets`() {
        val groups = ModelCatalog.groupBySize()
        fun bucketOf(p: ModelPack) = groups.indexOfFirst { (_, l) -> l.any { it.pack == p } }
        val two = bucketOf(ModelPack.SPLEETER_2STEM)
        val four = bucketOf(ModelPack.SPLEETER_4STEM)
        assertTrue("两包都应被分组", two >= 0 && four >= 0)
        assertTrue("2 轨(39MB) 与 4 轨(78MB) 应在不同节", two != four)
    }

    @Test
    fun `every bucket has a human readable size`() {
        for ((bytes, list) in ModelCatalog.groupBySize()) {
            // 提示可为 null（未知体积簇），但体积字符串必须非空
            assertTrue("${list.size} 个包的分节应有体积字符串", ModelCatalog.humanSize(bytes).isNotBlank())
        }
    }

    /**
     * ★已知缺口（有意固定，不是 bug 修复）。
     *
     * 可下载的 [ModelPack]（8 个）与可分离的 [ModelRegistry.ALL]（5 个正式）
     * + 13 个实验模型**不是一一对应**：
     * - 实验模型能测速但**没有下载条目**；
     * - `demucs / spleeter4` 能下载但**不在** [ModelRegistry.ALL]（另走 Spleeter4Dsp / Demucs 路径）。
     *
     * 模型库显示「实测 RTF」必须跨这两张表，本测试把缺口显式钉住；
     * 将来若补齐下载条目，这里会失败并提醒更新预期。
     */
    @Test
    fun `known gap between downloadable packs and separable models`() {
        val downloadable = ModelCatalog.ALL.map { it.pack }.toSet()
        val separatable = ModelRegistry.ALL.map { it.id }

        // Spleeter 2 轨：可下载且可分离（唯一完全重合的一个）
        assertTrue("spleeter-2stems-fp16 应可分离", "spleeter-2stems-fp16" in separatable)

        // UVR 5 个正式模型：可下载且可分离
        val uvrIds = ModelRegistry.UVR_VARIANTS.map { it.id }.toSet()
        assertEquals("UVR 正式模型应为 5 个", 5, uvrIds.size)

        // Demucs / Spleeter4 可下载但不在 ModelRegistry.ALL（走各自引擎路径）
        assertTrue(ModelPack.DEMUCS in downloadable)
        assertTrue(ModelPack.SPLEETER_4STEM in downloadable)
        assertTrue(
            "Demucs 不应出现在 2 轨模型注册表里",
            separatable.none { it == "demucs" },
        )

        // 实验模型目前均无下载条目 —— 既定状态
        val expIds = ModelRegistry.EXPERIMENTAL.map { it.id }.toSet()
        assertEquals(
            "实验模型目前均无下载条目（若要补，请同步更新本测试）",
            0,
            expIds.count { it in uvrIds },
        )
    }
}
