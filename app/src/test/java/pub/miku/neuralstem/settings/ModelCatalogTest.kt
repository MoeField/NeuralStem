package pub.miku.neuralstem.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ★模型清单单测。
 *
 * 重点锁死一个真机踩过的坑：**2-stem 与 4-stem 的 onnx 文件大小不同**
 * （都是 U-Net 架构但权重不同）。曾因为两者复用同一个字节常量，
 * 导致 4-stem 已下载完却显示「未安装」（真机实报）。
 */
class ModelCatalogTest {

    @Test
    fun `每个包都有非空文件与来源`() {
        for (info in ModelCatalog.ALL) {
            assertTrue("${info.pack} 应有文件", info.files.isNotEmpty())
            assertTrue("${info.pack} 应有许可说明", info.license.isNotBlank())
            assertTrue("${info.pack} 应有许可详情", info.licenseNote.isNotBlank())
            // Spleeter/Demucs 走 HF；UVR 走 GitHub Release（镜像不了）。
            assertTrue(
                "${info.pack} 来源应为 HF 或 GitHub：${info.sourceUrl}",
                info.sourceUrl.startsWith("https://huggingface.co/") ||
                    info.sourceUrl.startsWith("https://github.com/"),
            )
            assertTrue("${info.pack} 总体积应 > 0", info.totalBytes > 0)
        }
    }

    @Test
    fun `UVR 包必须如实标注许可未统一声明`() {
        val uvrPacks = listOf(
            ModelPack.UVR_9482, ModelPack.UVR_KARA, ModelPack.UVR_VOC_FT,
            ModelPack.UVR_INST_HQ_3, ModelPack.UVR_MAIN,
        )
        for (p in uvrPacks) {
            val info = ModelCatalog.info(p)
            assertTrue(
                "${p} 的许可必须写明“未统一声明”（不可声称自由许可）",
                info.license.contains("未统一声明"),
            )
            assertTrue(
                "${p} 的许可详情应提及上游风险",
                info.licenseNote.contains("未显式声明") || info.licenseNote.contains("未统一"),
            )
        }
    }

    @Test
    fun `UVR 模型落盘到 uvr 目录`() {
        for (p in listOf(ModelPack.UVR_9482, ModelPack.UVR_VOC_FT)) {
            assertEquals("uvr", ModelCatalog.info(p).files.first().targetSubdir)
        }
    }

    @Test
    fun `文件名与远端路径是一致的`() {
        for (info in ModelCatalog.ALL) {
            for (f in info.files) {
                assertTrue(
                    "${f.fileName} 应出现在 remotePath 里：${f.remotePath}",
                    f.remotePath.endsWith(f.fileName),
                )
                assertTrue("targetSubdir 不应为空", f.targetSubdir.isNotBlank())
            }
        }
    }

    /**
     * 核心回归：2-stem 与 4-stem 的期望字节数**必须不同**。
     *
     * 若有人把它们统一成一个常量，本测试会失败 —— 那正是真机 bug 的来源。
     */
    @Test
    fun `spleeter 2stem 与 4stem 的字节数不同`() {
        val two = ModelCatalog.info(ModelPack.SPLEETER_2STEM).files.first().expectedBytes
        val four = ModelCatalog.info(ModelPack.SPLEETER_4STEM).files.first().expectedBytes
        assertTrue("两者都应 > 0", two > 0 && four > 0)
        assertNotEquals("2-stem 与 4-stem 的 onnx 大小不同（架构同、权重不同）", two, four)
    }

    @Test
    fun `2stem 两轨字节数接近但不要求相等`() {
        val files = ModelCatalog.info(ModelPack.SPLEETER_2STEM).files
        assertEquals(2, files.size)
        val diff = abs(files[0].expectedBytes - files[1].expectedBytes)
        assertTrue("两轨大小应接近（差 <1MB），实际差 $diff", diff < 1_000_000)
    }

    /**
     * ★回归（2026-10-20 真机实报）：UVR 的 `expectedBytes` 曾写成抹零估值
     * `28_300_000` / `63_700_000`，与真实文件（29_704_738 / 66_759_522）差 1–3MB，
     * 远超 `isInstalled()` 的 ±64KB 容差 → **下载完成仍显示“未安装”**。
     *
     * 这个测试锁死真实值。改常量时若写回“约 xxMB”，这里立刻失败。
     */
    @Test
    fun `UVR 期望字节数必须是真实值而不是抹零估值`() {
        // 真机 ls -l 读回的真实大小（与官方 content-length 一致）
        val expected = mapOf(
            ModelPack.UVR_9482 to 29_704_738L,
            ModelPack.UVR_KARA to 29_704_738L,
            ModelPack.UVR_INST_HQ_3 to 66_759_522L,
            ModelPack.UVR_MAIN to 66_759_516L,
            ModelPack.UVR_VOC_FT to 66_762_795L,
        )
        // isInstalled() 的容差：±64KB
        val tolerance = 64L * 1024
        for ((pack, realBytes) in expected) {
            val declared = ModelCatalog.info(pack).files.single().expectedBytes
            val diff = abs(declared - realBytes)
            assertTrue(
                "$pack 的 expectedBytes=$declared 与真实值 $realBytes 相差 $diff 字节，" +
                    "超过容差 $tolerance —— 会导致“下载完仍显示未安装”",
                diff <= tolerance,
            )
        }
    }

    @Test
    fun `体积格式化可读`() {        assertEquals("39 MB", ModelCatalog.humanSize(39_000_000))
        assertEquals("181 MB", ModelCatalog.humanSize(181_000_000))
        assertTrue(ModelCatalog.humanSize(1_500).endsWith("KB"))
    }

    @Test
    fun `不同包的文件不应撞到同一文件名`() {
        // 注：UVR 五个包**共享** uvr/ 目录（故意的——引擎按这个目录找），
        // 但它们文件名各不相同，不会冲掉。
        val keys = ModelCatalog.ALL.flatMap { info ->
            info.files.map { "${it.targetSubdir}/${it.fileName}" }
        }
        assertEquals("不同包的（目录+文件名）不应重复", keys.size, keys.toSet().size)
    }
}
