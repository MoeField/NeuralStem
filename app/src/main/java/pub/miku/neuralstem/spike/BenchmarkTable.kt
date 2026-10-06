package pub.miku.neuralstem.spike

/**
 * ★G-2a：**内置 CPU → 模型基准表**（“CPU↔最大实时模型对照表”的静态数据源）。
 *
 * ## 为何
 * `DeviceBenchmark` 在本机实测最准，但**首启要花时间**（6 模型 ~2 分钟）。
 * 对**已知 CPU 型号**，可直接用**他人测好的**数据 → **免跑**（Q3：免跑只给**精确命中**）。
 *
 * ## 口径（用户定）
 * **纯推理 RTF**（合成块/耗时；跨设备可比）。UI 展示时注明“≈端到端”。
 * 数据来源：本项目实测（`doc/benchmark_2026-10-04.md` + `doc/.memory.md` M4 节）。
 *
 * ## 三源优先级（本类只负责“内置”这一源）
 * 1. **本机实测缓存**（`DeviceBenchmark.cached()`，最准，覆盖内置）
 * 2. **内置表精确命中**（同 CPU 型号）→ 免跑
 * 3. 区间推断（G-2b，仅预选默认，不用于免跑）
 */
object BenchmarkTable {

    /** 一条设备记录：CPU 型号 → {模型 id → 纯推理 RTF}。 */
    data class DeviceEntry(
        val cpu: String,
        val label: String,
        val rtf: Map<String, Float>,
    )

    /**
     * 内置表。CPU key 用 `Build.SOC_MODEL`（API 31+）/ `Build.HARDWARE`（旧）归一化后的**大写**串。
     *
     * 实测来源：
     * - `G100`（联发科 Helio G100，BF00A）：纯推理 RTF（LONG，3 轮中位）。
     * - `SM8650`（骁龙 8 Gen3）：纯推理 RTF（Gen3 全模型 benchmark）。
     */
    private val ENTRIES: List<DeviceEntry> = listOf(
        DeviceEntry(
            cpu = "G100",
            label = "联发科 Helio G100（BF00A）",
            rtf = mapOf(
                "spleeter-2stems-fp16" to 8.54f,
                "uvr-9482" to 1.03f,
                "uvr-KARA" to 1.03f,
                "uvr-Voc_FT" to 0.35f,
                "uvr-Inst_HQ_3" to 0.34f,
                "uvr-Main" to 0.34f,
            ),
        ),
        DeviceEntry(
            cpu = "SM8650",
            label = "骁龙 8 Gen3",
            rtf = mapOf(
                "spleeter-2stems-fp16" to 18.09f,
                "uvr-9482" to 2.25f,
                "uvr-Voc_FT" to 0.71f,
                // KARA / Inst_HQ_3 / Main 未单独记录 Gen3 纯推理 → 不填（避免编数据）
            ),
        ),
    )

    /**
     * 归一化 CPU 标识：去空格、大写（`Build.SOC_MODEL` 可能含空格/小写）。
     * 例：`"SM8650"`→`"SM8650"`；`"g100"`→`"G100"`。
     */
    internal fun normalize(cpu: String): String = cpu.trim().uppercase().replace(" ", "")

    /** 全部内置记录的 CPU（测试/诊断用）。 */
    val knownCpus: List<String> get() = ENTRIES.map { it.cpu }

    /**
     * **精确命中**查表：同 CPU 型号 → [DeviceBenchmark.ModelBenchmark] 映射。
     * 未命中返回空（→ 由上层决定“区间推断”或“本机实测”）。
     *
     * @param cpu `Build.SOC_MODEL`（API 31+）或 `Build.HARDWARE`
     */
    fun lookup(cpu: String): Map<String, DeviceBenchmark.ModelBenchmark> {
        val key = normalize(cpu)
        val e = ENTRIES.firstOrNull { normalize(it.cpu) == key } ?: return emptyMap()
        // ms 无意义（非本机）；给 0，仅 rtf 有效。
        return e.rtf.mapValues { (id, rtf) -> DeviceBenchmark.ModelBenchmark(id, rtf, 0L) }
    }

    /** 该 CPU 是否有内置记录（= 可免跑）。 */
    fun hasExact(cpu: String): Boolean = ENTRIES.any { normalize(it.cpu) == normalize(cpu) }

    /**
     * ★G-2b：**天梯区间推断**（仅用于**预选默认值**，**不用于免跑**）。
     *
     * 思路（“125% 跑分 → 可跑 100% 基准实时模型”的推广）：
     * 模型间的 RTF **比例**在同一设备上大致稳定。故：
     * 1. 用本机**已测的一个锚模型**（如 Spleeter，SHORT 很快）的 RTF 当锚点；
     * 2. 拿一个**参考内置记录**（含该锚模型）作基准表；
     * 3. **按锚点比例线性缩放**整张表 → 预估其余模型 RTF。
     *
     * @param anchorId 锚模型 id（必须存在于参考表中）
     * @param anchorRtf 本机实测的锚点 RTF
     * @return 预估 RTF（modelId → rtf）；无可用参考表/锚点不在表中 → 空（不推断）
     */
    fun estimateByAnchor(
        anchorId: String,
        anchorRtf: Float,
    ): Map<String, Float> {
        if (anchorRtf <= 0f) return emptyMap()
        // 选一个含锚模型的参考记录（优先 G100，因数据最全）
        val ref = ENTRIES.firstOrNull { it.rtf.containsKey(anchorId) } ?: return emptyMap()
        val base = ref.rtf[anchorId] ?: return emptyMap()
        if (base <= 0f) return emptyMap()
        val scale = anchorRtf / base
        return ref.rtf.mapValues { (_, rtf) -> rtf * scale }
    }

    /**
     * 按“实时门槛”推荐**默认模型**（RTF ≥ [realtimeThreshold] 者中 RTF 最大 = 最快）。
     * 用于 G-2b 的“仅预选默认值”。
     *
     * @param rtfById 某设备的 RTF 表（可为预估）
     * @param installedIds 已安装模型（仅在这些里选）
     * @return 推荐模型 id；若无满足者 → null
     */
    fun pickDefault(
        rtfById: Map<String, Float>,
        installedIds: Set<String>,
        realtimeThreshold: Float = 2f,
    ): String? =
        rtfById.entries
            .filter { it.key in installedIds && it.value >= realtimeThreshold }
            .maxByOrNull { it.value }
            ?.key
}
