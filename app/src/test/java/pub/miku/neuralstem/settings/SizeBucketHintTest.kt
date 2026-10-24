package pub.miku.neuralstem.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ModelCatalog.sizeBucketHint` 的单测。
 *
 * ## 为何要写
 * 2026-10-25 重写了这个函数 —— 旧版**硬编码**了 BF00A 的 RTF：
 * ```kotlin
 * bytes >= 60_000_000L -> "≈0.34–0.35×"   // ← Helio G100 的值
 * ```
 * 但三设备实测证明：**同样 64MB，Gen3 上是 0.71×**（快一倍）。
 * 硬编码会让 Gen3 用户看到"0.34×"而怀疑模型装错了。
 *
 * ⇒ 新版从**本机 benchmark 缓存**取中位数；本机没测过 → **返回 null**（不显示）。
 * 这组测试把这套语义钉死。
 */
class SizeBucketHintTest {

    /** 找某个体积的节键（用真实的 [ModelCatalog.ALL] 数据）。 */
    private fun bucketBytesFor(pack: ModelPack): Long =
        ModelCatalog.ALL.first { it.pack == pack }.totalBytes

    /** 所有包 → ModelSpec.id 的映射（供测试用）。 */
    private val idOf: (List<ModelPackInfo>) -> List<String> = { infos ->
        infos.mapNotNull { modelIdOf(it.pack) }
    }

    @Test
    fun `本机测过该节 → 返回本机值`() {
        val spleeter = ModelPack.SPLEETER_2STEM
        val bytes = bucketBytesFor(spleeter)
        val id = modelIdOf(spleeter)!!

        val hint = ModelCatalog.sizeBucketHint(bytes, mapOf(id to 21.26f), idOf)

        assertNotNull("测过就应显示", hint)
        assertEquals("本机 21.26×", hint)
    }

    @Test
    fun `本机没测过 → 返回 null（不编造数字）`() {
        val bytes = bucketBytesFor(ModelPack.SPLEETER_2STEM)

        val hint = ModelCatalog.sizeBucketHint(bytes, emptyMap(), idOf)

        assertNull("未测不能硬编码一个数字", hint)
    }

    @Test
    fun `同一节内多个模型取中位数（抗离群）`() {
        // 64MB 节有多个 UVR 模型：给一组值，其中一个是离群大值
        val bytes = bucketBytesFor(ModelPack.UVR_VOC_FT)
        val infos = ModelCatalog.ALL.filter { it.totalBytes == bytes }
        val ids = idOf(infos)
        assertTrue("64MB 节应有多个模型", ids.size >= 3)

        // 构造：前 3 个 = 0.70，最后一个 = 99.0（离群）
        val rtf = ids.mapIndexed { i, id ->
            id to if (i < ids.size - 1) 0.70f else 99.0f
        }.toMap()

        val hint = ModelCatalog.sizeBucketHint(bytes, rtf, idOf)

        assertNotNull(hint)
        assertTrue(
            "中位数应接近 0.70（不被离群值拉走），实际=$hint",
            hint!!.contains("0.70"),
        )
    }

    @Test
    fun `只测了节内一个模型也能出值`() {
        val bytes = bucketBytesFor(ModelPack.UVR_VOC_FT)
        val infos = ModelCatalog.ALL.filter { it.totalBytes == bytes }
        val firstId = idOf(infos).first()

        val hint = ModelCatalog.sizeBucketHint(bytes, mapOf(firstId to 0.71f), idOf)

        assertNotNull("测了一个就够了", hint)
        assertTrue(hint!!.contains("0.71"))
    }

    @Test
    fun `不同设备同一节得到不同值（证明没硬编码）`() {
        val bytes = bucketBytesFor(ModelPack.UVR_VOC_FT)
        val infos = ModelCatalog.ALL.filter { it.totalBytes == bytes }
        val ids = idOf(infos)

        val bf00a = ids.associateWith { 0.35f }
        val gen3 = ids.associateWith { 0.71f }

        val h1 = ModelCatalog.sizeBucketHint(bytes, bf00a, idOf)
        val h2 = ModelCatalog.sizeBucketHint(bytes, gen3, idOf)

        assertTrue("BF00A 应显示 0.35×", h1!!.contains("0.35"))
        assertTrue("Gen3 应显示 0.71×", h2!!.contains("0.71"))
        assertTrue("两台设备显示必须不同（否则说明硬编码了）", h1 != h2)
    }

    @Test
    fun `每个 ModelPack 都能映射到 ModelSpec id（4 轨与 Demucs 用固定串）`() {
        // 这个测试防止将来新增 ModelPack 时忘了补 modelIdOf 分支
        val missing = ModelPack.entries.filter { modelIdOf(it) == null }
        assertEquals("不应有无法映射的包，缺：$missing", emptyList<ModelPack>(), missing)
    }
}
