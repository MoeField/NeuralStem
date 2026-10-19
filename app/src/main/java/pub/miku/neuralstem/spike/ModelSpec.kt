package pub.miku.neuralstem.spike

import android.content.Context
import java.io.File

/**
 * 分离模型注册表(阶段 M2 任务 A)。
 *
 * ## 为什么需要它
 * 阶段 M 之前，模型信息散落在代码里:`MODE_SPLEETER/MODE_UVR` 常量、
 * 硬编码的文件名(`vocals.fp16.onnx`)、`resolveModels` 里的目录判断。
 * 新增「设备 benchmark 分组」「按用途筛选」「stem 顺序修正」后，这些信息需要**集中**。
 *
 * ## 关键:stemOrder(修 Inst 系反标签)
 * UVR-MDX 运行时是「模型只预测一个 stem → non_vocals = 源 − 预测」(残差法)，
 * 而 [SherpaSourceSeparation] 的输出恒为 `vocals` / `accompaniment` 两个流。
 * **Inst 系模型(inst = instrumental)预测的是伴奏**，于是:
 *   - 运行时的 `stem[0]`("vocals") 里装的其实是 **伴奏**
 *   - 运行时的 `stem[1]`("accompaniment") 里装的其实是 **人声**
 * 即 **两轨完全对调**。
 *
 * 阶段 M 已用互相关实证(26s 曲):
 * ```
 * Inst_HQ_3.vocals  ↔ Voc_FT.accompaniment  NCC = +0.992  (高度相似)
 * Inst_HQ_3.vocals  ↔ Voc_FT.vocals         NCC = +0.032  (不相关)
 * ```
 * ⇒ 对 Inst 系模型，App 侧必须把两轨**交换**后再落盘/展示。
 *
 * ## 为什么用元数据而不是「名字含 Inst 就交换」
 * 名字约定脆(官方 release 有 16 个 UVR 变体，命名并不完全统一)。
 * 显式列在注册表里，谁是什么一目了然，且有单测兜底。
 */
enum class Purpose {
    /** 人声导向:人声干净，伴奏可能有残留 */
    VOCALS,

    /** 伴奏/器乐导向:伴奏干净，人声极少 */
    INSTRUMENTAL,

    /** 通用 */
    GENERAL,
}

/**
 * 模型输出的「stem 顺序」——决定 App 要不要交换两轨。
 *
 * - [VOCALS_FIRST]:运行时 stem[0]=人声、stem[1]=伴奏(**默认，正常**)
 * - [ACCOMP_FIRST]:运行时 stem[0]=伴奏、stem[1]=人声(**需交换**，如 Inst 系)
 */
enum class StemOrder {
    VOCALS_FIRST,
    ACCOMP_FIRST,
}

/**
 * 一个分离模型的元信息。
 *
 * @param id 稳定标识(用于缓存/偏好 key，勿随意改)
 * @param displayName UI 显示名
 * @param fileName 文件名(相对模型目录)
 * @param engine [SherpaSourceSeparation.MODE_SPLEETER] / [SherpaSourceSeparation.MODE_UVR]
 * @param sizeMb 体积(MB，用于 UI 展示与低配机过滤参考)
 * @param purpose 用途标签
 * @param stemOrder 输出 stem 顺序(见类注释)
 * @param spleeterAccompFile Spleeter 专用:伴奏模型文件名(engine=SPLEETER 时非空)
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val engine: Int,
    val sizeMb: Int,
    val purpose: Purpose,
    val stemOrder: StemOrder = StemOrder.VOCALS_FIRST,
    val spleeterAccompFile: String? = null,
) {
    /** 是否需要在落盘前交换两轨(Inst 系为 true)。 */
    val needsStemSwap: Boolean get() = stemOrder == StemOrder.ACCOMP_FIRST

    /**
     * ★该模型的 onnx 是否已装到 `filesDir/{sherpa,uvr}/`。
     *
     * 供 UI 判断“该档能不能点”（未装的候选不出按钮）。
     * - Spleeter 2-stem：需两个文件都在（`vocals` + `accompaniment`，后者在 [spleeterAccompFile]）
     * - UVR：只需 [fileName] 在 `filesDir/uvr/`
     */
    fun isInstalled(app: Context): Boolean {
        val dir = when (engine) {
            SherpaSourceSeparation.MODE_SPLEETER -> File(app.filesDir, "sherpa")
            else -> File(app.filesDir, "uvr")
        }
        val main = File(dir, fileName)
        if (!main.exists() || main.length() <= 0L) return false
        val accomp = spleeterAccompFile ?: return true
        val a = File(dir, accomp)
        return a.exists() && a.length() > 0L
    }
}

object ModelRegistry {

    /** Spleeter 2-stem(默认引擎)。 */
    val SPLEETER = ModelSpec(
        id = "spleeter-2stems-fp16",
        displayName = "Spleeter 2-stem",
        fileName = "vocals.fp16.onnx",
        engine = SherpaSourceSeparation.MODE_SPLEETER,
        sizeMb = 39,
        purpose = Purpose.GENERAL,
        stemOrder = StemOrder.VOCALS_FIRST,
        spleeterAccompFile = "accompaniment.fp16.onnx",
    )

    /**
     * 全部 UVR-MDX 变体(阶段 M 精选 5 个)。
     *
     * stemOrder 判定依据:
     * - `Inst_*` / `Instrumental` 系 → [StemOrder.ACCOMP_FIRST](已实证 Inst_HQ_3 反了)
     * - 其余(Voc_FT / 9482 / KARA / Main)→ [StemOrder.VOCALS_FIRST](已实证正常)
     */
    val UVR_VARIANTS: List<ModelSpec> = listOf(
        ModelSpec(
            id = "uvr-Voc_FT",
            displayName = "UVR Voc_FT",
            fileName = "UVR-MDX-NET-Voc_FT.onnx",
            engine = SherpaSourceSeparation.MODE_UVR,
            sizeMb = 64,
            purpose = Purpose.VOCALS,
        ),
        ModelSpec(
            id = "uvr-9482",
            displayName = "UVR 9482",
            fileName = "UVR_MDXNET_9482.onnx",
            engine = SherpaSourceSeparation.MODE_UVR,
            sizeMb = 28,
            purpose = Purpose.GENERAL,
        ),
        ModelSpec(
            id = "uvr-KARA",
            displayName = "UVR KARA",
            fileName = "UVR_MDXNET_KARA.onnx",
            engine = SherpaSourceSeparation.MODE_UVR,
            sizeMb = 28,
            purpose = Purpose.INSTRUMENTAL,
        ),
        ModelSpec(
            id = "uvr-Inst_HQ_3",
            displayName = "UVR Inst_HQ_3",
            fileName = "UVR-MDX-NET-Inst_HQ_3.onnx",
            engine = SherpaSourceSeparation.MODE_UVR,
            sizeMb = 64,
            purpose = Purpose.INSTRUMENTAL,
            // ★ Inst 系:运行时两轨对调(实证见类注释)
            stemOrder = StemOrder.ACCOMP_FIRST,
        ),
        ModelSpec(
            id = "uvr-Main",
            displayName = "UVR Main",
            fileName = "UVR_MDXNET_Main.onnx",
            engine = SherpaSourceSeparation.MODE_UVR,
            sizeMb = 64,
            purpose = Purpose.GENERAL,
        ),
    )

    /** 全部模型(Spleeter 在前)。 */
    val ALL: List<ModelSpec> = listOf(SPLEETER) + UVR_VARIANTS

    /**
     * ★实验模型（13 个）：sherpa-onnx 官方 release 里**其余** UVR/MDX 变体。
     *
     * ## 为何单独一个列表
     * 这些模型**不参与档位/默认引擎/设置页选择** —— 因为：
     * - 它们的音质定位与现有 5 个重叠（只是不同作者的训练集不同）；
     * - 全部暴露会让 UI 臃肿，且大部分实测比 9482 还慢。
     * ➜ **仅供 benchmark 测速 / 手动选用**（不写入 [ALL]）。
     *
     * ## stem 顺序
     * `Inst_*` / `Inst_Main` 系 → [StemOrder.ACCOMP_FIRST]（inst = instrumental 导向）；
     * 其余（含 Crowd / KARA_2 / _9703 系）→ [StemOrder.VOCALS_FIRST]。
     * ⚠️ 这批**未做 NCC 实证**，如需正式启用必须先验证反标签（参 Inst_HQ_3 的教训）。
     */
    val EXPERIMENTAL: List<ModelSpec> = listOf(
        // ---- Inst 系（乐器导向，62/56MB，dim_f 3072 → 预期 ~0.35×）----
        exp("uvr-Inst_1", "UVR Inst_1", "UVR-MDX-NET-Inst_1.onnx", 64, Purpose.INSTRUMENTAL, StemOrder.ACCOMP_FIRST),
        exp("uvr-Inst_2", "UVR Inst_2", "UVR-MDX-NET-Inst_2.onnx", 64, Purpose.INSTRUMENTAL, StemOrder.ACCOMP_FIRST),
        exp("uvr-Inst_3", "UVR Inst_3", "UVR-MDX-NET-Inst_3.onnx", 64, Purpose.INSTRUMENTAL, StemOrder.ACCOMP_FIRST),
        exp("uvr-Inst_HQ_1", "UVR Inst_HQ_1", "UVR-MDX-NET-Inst_HQ_1.onnx", 64, Purpose.INSTRUMENTAL, StemOrder.ACCOMP_FIRST),
        exp("uvr-Inst_HQ_2", "UVR Inst_HQ_2", "UVR-MDX-NET-Inst_HQ_2.onnx", 64, Purpose.INSTRUMENTAL, StemOrder.ACCOMP_FIRST),
        exp("uvr-Inst_HQ_4", "UVR Inst_HQ_4", "UVR-MDX-NET-Inst_HQ_4.onnx", 56, Purpose.INSTRUMENTAL, StemOrder.ACCOMP_FIRST),
        exp("uvr-Inst_HQ_5", "UVR Inst_HQ_5", "UVR-MDX-NET-Inst_HQ_5.onnx", 56, Purpose.INSTRUMENTAL, StemOrder.ACCOMP_FIRST),
        exp("uvr-Inst_Main", "UVR Inst_Main", "UVR-MDX-NET-Inst_Main.onnx", 50, Purpose.INSTRUMENTAL, StemOrder.ACCOMP_FIRST),

        // ---- 人声/合唱 ----
        exp("uvr-Crowd_HQ_1", "UVR Crowd_HQ_1", "UVR-MDX-NET_Crowd_HQ_1.onnx", 56, Purpose.VOCALS),

        // ---- 通用（28MB 系：dim_f 2048 → 预期 ~1.0×）----
        exp("uvr-9703", "UVR 1_9703", "UVR_MDXNET_1_9703.onnx", 28, Purpose.GENERAL),
        exp("uvr-9682", "UVR 2_9682", "UVR_MDXNET_2_9682.onnx", 28, Purpose.GENERAL),
        exp("uvr-9662", "UVR 3_9662", "UVR_MDXNET_3_9662.onnx", 28, Purpose.GENERAL),

        // ---- 卡拉OK 2（50MB，中等档）----
        exp("uvr-KARA_2", "UVR KARA_2", "UVR_MDXNET_KARA_2.onnx", 50, Purpose.INSTRUMENTAL),
    )

    /** benchmark 用：已注册 5 个 + 实验 13 个。 */
    val BENCHMARKABLE: List<ModelSpec> = ALL + EXPERIMENTAL

    private fun exp(
        id: String,
        name: String,
        file: String,
        sizeMb: Int,
        purpose: Purpose,
        stemOrder: StemOrder = StemOrder.VOCALS_FIRST,
    ) = ModelSpec(
        id = id,
        displayName = name,
        fileName = file,
        engine = SherpaSourceSeparation.MODE_UVR,
        sizeMb = sizeMb,
        purpose = purpose,
        stemOrder = stemOrder,
    )

    /** 按 id 查(找不到返回 null)。 */
    fun byId(id: String): ModelSpec? = ALL.firstOrNull { it.id == id }

    /** 默认引擎。 */
    val DEFAULT: ModelSpec = SPLEETER
}
