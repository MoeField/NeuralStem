package pub.miku.neuralstem.settings

import java.io.File
import kotlin.math.abs

/**
 * ★模型一键下载：**模型清单**（唯一真源）。
 *
 * ## 为什么有这个文件
 * 此前模型必须用 adb push 到 `filesDir/{sherpa,spleeter4,demucs}/` 才能用分离功能 ——
 * 发布出去别人装了 APK 也**打不开功能**。本清单把"从哪下、多大、谁授权"集中到一处，
 * 供 [ModelDownloader] 与设置页 UI 使用。
 *
 * ## 来源（均已实测可用，见 git 记录）
 * | 模型 | 来源 | 格式 | 许可 |
 * |---|---|---|---|
 * | Spleeter 2-stem | HF `csukuangfj/sherpa-onnx-spleeter-2stems-fp16` | 单个 .onnx | MIT（Deezer） |
 * | Spleeter 4-stem | HF `Best-Practice/spleeter-4stems-onnx` | 单个 .onnx | **Apache-2.0** |
 * | Demucs | HF `timcsy/demucs-web-onnx` | 单个 .onnx | 上游 MIT（转换仓库未额外声明） |
 *
 * > 注：sherpa-onnx 的 GitHub Release 也提供 2-stem，但打包为 `.tar.bz2`
 * > （Android 解 bz2 需额外依赖）。HF 上的 `csukuangfj` 仓库是**同样的文件、单独的 .onnx**
 * > → 统一走 HF，零额外依赖。
 */
enum class ModelPack(val id: String) {
    /** Spleeter 2-stem fp16 —— 默认引擎，19.7MB × 2。 */
    SPLEETER_2STEM("spleeter-2stem"),

    /** Spleeter 4-stem fp16 —— 人声/鼓/贝斯/其他，19.7MB × 4。 */
    SPLEETER_4STEM("spleeter-4stem"),

    /** Demucs htdemucs（SOTA 音质）—— 172MB 单文件，仅旗舰机可用。 */
    DEMUCS("demucs"),

    // ---- UVR / MDX-Net 系（sherpa-onnx 官方 release 转发）----

    /** UVR Voc_FT —— 人声导向，64MB，实测 0.34× 实时。 */
    UVR_VOC_FT("uvr-voc-ft"),

    /** UVR 9482 —— 通用，28MB，实测 1.03×（UVR 里最快）。 */
    UVR_9482("uvr-9482"),

    /** UVR KARA —— 卡拉OK/伴奏导向，28MB，实测 1.03×。 */
    UVR_KARA("uvr-kara"),

    /** UVR Inst_HQ_3 —— 乐器导向（去人声最强），64MB，0.34×。 */
    UVR_INST_HQ_3("uvr-inst-hq3"),

    /** UVR Main —— 通用，64MB，0.34×。 */
    UVR_MAIN("uvr-main"),
}

/** 一个待下载的文件。 */
data class ModelFile(
    /** 远端路径（拼在源域名后面；HF 格式：/<repo>/resolve/main/<file>）。 */
    val remotePath: String,
    /** 落地到 `filesDir/<targetSubdir>/<fileName>`。 */
    val targetSubdir: String,
    val fileName: String,
    /** 期望字节数（用于进度与完整性初判）；0 = 未知。 */
    val expectedBytes: Long = 0L,
)

/** 一个模型包的完整描述。 */
data class ModelPackInfo(
    val pack: ModelPack,
    val displayName: String,
    /** 许可短名（UI 展示用）。 */
    val license: String,
    /** 许可详情（弹窗正文用；**必须如实**，含未声明的情形）。 */
    val licenseNote: String,
    /** 来源页（用户可自行核对）。 */
    val sourceUrl: String,
    val files: List<ModelFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.expectedBytes }
}

object ModelCatalog {

    private const val HF_SPLEETER2 = "csukuangfj/sherpa-onnx-spleeter-2stems-fp16"
    private const val HF_SPLEETER4 = "Best-Practice/spleeter-4stems-onnx"
    private const val HF_DEMUCS = "timcsy/demucs-web-onnx"

    /**
     * UVR 模型的来源：**sherpa-onnx 官方 GitHub Release**（不是 HF）。
     *
     * 为何不用 HF：这批 UVR/MDX 模型就是 k2-fsa 在 GitHub Release 里转发的，
     * HF 上没有对应镜像（或不同步）。
     *
     * ⚠️ **hf-mirror 镜像不了 GitHub** → UVR 在“自动切镜像”时仍会走 github.com，
     *    国内网络可能很慢。已在下拉源说明里告知用户。
     */
    private const val GH_UVR_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models"

    /**
     * 期望字节数（用于完整性校验）。
     *
     * ⚠️ **2-stem 与 4-stem 的文件大小并不相同**（4-stem 每个大 ~33KB）：
     * 两者都是 U-Net 架构但权重不同，不能复用同一个常量。
     * 曾因复用导致“已下载但 UI 显示未安装”（真机实报）。
     */
    private const val SPLEETER2_VOCALS_BYTES = 19_681_017L
    private const val SPLEETER2_ACCOMP_BYTES = 19_681_024L
    private const val SPLEETER4_BYTES = 19_714_141L

    val ALL: List<ModelPackInfo> = listOf(
        ModelPackInfo(
            pack = ModelPack.SPLEETER_2STEM,
            displayName = "Spleeter 2 轨（人声 + 伴奏）",
            license = "MIT",
            licenseNote = "模型源自 Deezer Spleeter（MIT）。" +
                "本 ONNX 文件由 sherpa-onnx 官方账号（csukuangfj）转换分发。",
            sourceUrl = "https://huggingface.co/$HF_SPLEETER2",
            files = listOf(
                ModelFile(
                    "/$HF_SPLEETER2/resolve/main/vocals.fp16.onnx",
                    "sherpa", "vocals.fp16.onnx", SPLEETER2_VOCALS_BYTES,
                ),
                ModelFile(
                    "/$HF_SPLEETER2/resolve/main/accompaniment.fp16.onnx",
                    "sherpa", "accompaniment.fp16.onnx", SPLEETER2_ACCOMP_BYTES,
                ),
            ),
        ),
        ModelPackInfo(
            pack = ModelPack.SPLEETER_4STEM,
            displayName = "Spleeter 4 轨（人声/鼓/贝斯/其他）",
            license = "Apache-2.0",
            licenseNote = "模型源自 Deezer Spleeter 架构，转换与分发仓库标注 Apache-2.0。" +
                "体积 4 × 19.7MB，音质与 2 轨同代（只提升粒度）。",
            sourceUrl = "https://huggingface.co/$HF_SPLEETER4",
            files = listOf(
                ModelFile("/$HF_SPLEETER4/resolve/main/vocals.fp16.onnx", "spleeter4", "vocals.fp16.onnx", SPLEETER4_BYTES),
                ModelFile("/$HF_SPLEETER4/resolve/main/drums.fp16.onnx", "spleeter4", "drums.fp16.onnx", SPLEETER4_BYTES),
                ModelFile("/$HF_SPLEETER4/resolve/main/bass.fp16.onnx", "spleeter4", "bass.fp16.onnx", SPLEETER4_BYTES),
                ModelFile("/$HF_SPLEETER4/resolve/main/other.fp16.onnx", "spleeter4", "other.fp16.onnx", SPLEETER4_BYTES),
            ),
        ),
        ModelPackInfo(
            pack = ModelPack.DEMUCS,
            displayName = "Demucs（htdemucs，音质优先）",
            license = "MIT（上游）",
            licenseNote = "模型权重源自 Meta Demucs（MIT）。" +
                "本文件为社区转换的 ONNX 格式（转换仓库未额外声明许可），按上游 MIT 使用。" +
                "⚠️ 体积 172MB，仅建议旗舰机；中端机分离慢于播放。",
            sourceUrl = "https://huggingface.co/$HF_DEMUCS",
            files = listOf(
                ModelFile("/$HF_DEMUCS/resolve/main/htdemucs_embedded.onnx", "demucs", "htdemucs_embedded.onnx", 180_534_758L),
            ),
        ),
    ) + uvrPacks()

    /**
     * ★UVR / MDX-Net 系 5 个（全部来自 sherpa-onnx 官方 GitHub Release）。
     *
     * ## 为何单独抽成函数
     * 这 5 个的许可/来源/体积都同构，抽出来避免重复 5 遍模板。
     *
     * ## 许可说明（重要，必须如实告知用户）
     * 这批模型由 **k2-fsa（sherpa-onnx 作者）转发**，与 Spleeter 在同一个 release；
     * 但 **上游 UVR/MDX-Net 模型权重多数没有显式许可声明**（部分源自 Sony MDX 研究发布，
     * 常带研究/非商业限制）。⇒ 弹窗与 NOTICE 里必须写清，不能声称“自由许可”。
     *
     * ## 体积与速度（真机 BF00A 实测）
     * 9482 / KARA 各 28MB，1.03×（可用）；
     * Voc_FT / Inst_HQ_3 / Main 各 64MB，**0.34×**（4 分钟歌要 12 分钟）。
     */
    private fun uvrPacks(): List<ModelPackInfo> {
        fun pack(
            p: ModelPack,
            title: String,
            file: String,
            bytes: Long,
            speedNote: String,
        ) = ModelPackInfo(
            pack = p,
            displayName = title,
            license = "未统一声明",
            licenseNote = "模型由 **k2-fsa（sherpa-onnx 作者）** 在官方 GitHub Release 转发，" +
                "与 Spleeter 同一个 release。⚠️ **上游 UVR/MDX-Net 权重多数未显式声明许可**" +
                "（部分源自 Sony MDX 研究发布，常带研究/非商业限制）—— **仅供个人学习研究**，" +
                "商用请自行联系模型作者或改用 Spleeter。" +
                "\n\n速度（$speedNote）。",
            sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/source-separation-models",
            files = listOf(
                ModelFile(
                    remotePath = file,   // 完整 URL（含 https://）→ 下载器识别后不走 base
                    targetSubdir = "uvr",
                    fileName = file.substringAfterLast('/'),
                    expectedBytes = bytes,
                ),
            ),
        )

        return listOf(
            pack(
                ModelPack.UVR_9482, "UVR 9482（UVR 里最快）",
                "$GH_UVR_BASE/UVR_MDXNET_9482.onnx", 28_300_000L, "实测 1.03×",
            ),
            pack(
                ModelPack.UVR_KARA, "UVR KARA（伴奏/卡拉OK 导向）",
                "$GH_UVR_BASE/UVR_MDXNET_KARA.onnx", 28_300_000L, "实测 1.03×",
            ),
            pack(
                ModelPack.UVR_VOC_FT, "UVR Voc_FT（人声导向）",
                "$GH_UVR_BASE/UVR-MDX-NET-Voc_FT.onnx", 63_700_000L, "实测 0.34×，4 分钟歌约 12 分钟",
            ),
            pack(
                ModelPack.UVR_INST_HQ_3, "UVR Inst_HQ_3（去人声最强）",
                "$GH_UVR_BASE/UVR-MDX-NET-Inst_HQ_3.onnx", 63_700_000L, "实测 0.34×，4 分钟歌约 12 分钟",
            ),
            pack(
                ModelPack.UVR_MAIN, "UVR Main（通用）",
                "$GH_UVR_BASE/UVR_MDXNET_Main.onnx", 63_700_000L, "实测 0.34×，4 分钟歌约 12 分钟",
            ),
        )
    }

    fun info(pack: ModelPack): ModelPackInfo = ALL.first { it.pack == pack }

    /**
     * 该包是否已全部安装。
     *
     * 判定：文件存在且非空；若知道 [ModelFile.expectedBytes] 则额外校验大小。
     *
     * ★为何用**答差**而不是精确相等：fp16 onnx 的四个 stem 文件大小
     * 略有差异（实测 19,714,139 / 140 / 140 / 141）—— 它们自描述头长度不一。
     * 早期用精确相等 → “已下载但显示未安装”（真机实报）。
     */
    fun isInstalled(filesDir: File, pack: ModelPack): Boolean {
        val info = info(pack)
        return info.files.all { f ->
            val target = File(File(filesDir, f.targetSubdir), f.fileName)
            if (!target.exists() || target.length() <= 0L) return@all false
            if (f.expectedBytes <= 0L) return@all true
            val diff = abs(target.length() - f.expectedBytes)
            diff <= SIZE_TOLERANCE_BYTES
        }
    }

    /** 文件大小容差（±64KB）：足够区分“下完”与“下了一半”，又能吸收头长度差异。 */
    private const val SIZE_TOLERANCE_BYTES = 64L * 1024

    /** 已安装的包。 */
    fun installed(filesDir: File): List<ModelPack> =
        ModelPack.entries.filter { isInstalled(filesDir, it) }

    /** 人类可读体积。 */
    fun humanSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }
}

/**
 * ★下载源。默认 [AUTO]：运行时探测，优选能连通的那个。
 *
 * ## 为什么直接探目标域名，而不是 ping google
 * - Android 上 ICMP（ping）常被禁，且 "Google 通" ≠ "HuggingFace 通"
 *   （企业/校园网常只封部分域名）；
 * - 直接对目标域名发 HEAD 最准，且零额外依赖。
 */
enum class ModelSourcePreference { AUTO, HUGGINGFACE, HF_MIRROR }

object ModelSources {
    const val HUGGINGFACE = "https://huggingface.co"
    const val HF_MIRROR = "https://hf-mirror.com"

    fun baseUrl(pref: ModelSourcePreference): String? = when (pref) {
        ModelSourcePreference.HUGGINGFACE -> HUGGINGFACE
        ModelSourcePreference.HF_MIRROR -> HF_MIRROR
        ModelSourcePreference.AUTO -> null // 需探测
    }

    fun label(pref: ModelSourcePreference): String = when (pref) {
        ModelSourcePreference.AUTO -> "自动"
        ModelSourcePreference.HUGGINGFACE -> "HuggingFace（官方）"
        ModelSourcePreference.HF_MIRROR -> "hf-mirror（国内镜像）"
    }
}
