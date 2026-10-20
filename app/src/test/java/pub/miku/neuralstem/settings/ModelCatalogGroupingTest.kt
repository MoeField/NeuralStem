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
     * ★模型库与可分离模型的对应关系（2026-10-20 重写）。
     *
     * 背景：实验模型原本**没有下载条目**（只靠 adb push），因为它们没做过
     * stem 顺序实证。2026-10-20 完成 NCC 实证后开放了 12 个，本测试锁死对应关系。
     *
     * 两个方向都要保证：
     * 1. **每个实验模型要么有下载条目、要么在“明确排除”名单里** ——
     *    否则用户会在分离策略里看到它、但模型库里下载不到（幽灵模型）。
     * 2. **排除名单里的不能有下载条目** —— 否则等于把未验证的推给用户。
     */
    @Test
    fun `实验模型的下载条目与 NCC 实证结论一一对应`() {
        // ★因 NCC 证据不足而**明确排除**的模型（见 doc/stem_order_evidence.md）
        val excluded = setOf("uvr-Crowd_HQ_1")

        assertEquals(
            "EXPERIMENTAL 总数变了（新增模型时请同步更新本测试）",
            13, ModelRegistry.EXPERIMENTAL.size,
        )
        val shouldBeDownloadable = ModelRegistry.EXPERIMENTAL.filter { it.id !in excluded }
        assertEquals("应有 12 个可下载的实验模型", 12, shouldBeDownloadable.size)

        val downloadableFiles = ModelCatalog.ALL.flatMap { it.files }.map { it.fileName }.toSet()
        for (spec in ModelRegistry.EXPERIMENTAL) {
            val hasEntry = spec.fileName in downloadableFiles
            if (spec.id in excluded) {
                assertTrue(
                    "${spec.id} 因 NCC 证据不足被排除，不应有下载条目（否则等于推给用户）",
                    !hasEntry,
                )
            } else {
                assertTrue(
                    "${spec.id} 已通过 NCC 实证，模型库应有下载条目（否则是幽灵模型）",
                    hasEntry,
                )
            }
        }
    }

    /** Demucs / Spleeter4 可下载但不属于 2 轨模型（走各自引擎路径）。 */
    @Test
    fun `demucs 与 spleeter4 可下载但不属于 2 轨模型`() {
        val downloadable = ModelCatalog.ALL.map { it.pack }.toSet()
        val separatable2 = ModelRegistry.ALL.map { it.id }

        assertTrue(ModelPack.DEMUCS in downloadable)
        assertTrue(ModelPack.SPLEETER_4STEM in downloadable)
        assertTrue(
            "Demucs 不应出现在 2 轨模型注册表里",
            separatable2.none { it == "demucs" },
        )
        assertTrue("spleeter-2stems-fp16 应可分离", "spleeter-2stems-fp16" in separatable2)
    }
}
