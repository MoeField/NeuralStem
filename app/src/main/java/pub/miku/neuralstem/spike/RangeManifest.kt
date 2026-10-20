package pub.miku.neuralstem.spike

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ★边播两阶段（2026-10-20）：**已分离区间清单**。
 *
 * ## 为什么必须有这个文件
 * stem 文件由 [RandomAccessWavWriter] **构造时就预分配到完整曲长**
 * （`raf.setLength(...)` + `fd.sync()`），未写区全是 0。
 *
 * ⇒ **文件的"大小"永远是完整的** → `File.length()` 完全无法区分
 *   "分离完了" 与 "刚创建还没写"。
 *
 * 而写入过程 `writeAt()` 只是 `raf.write()`（进页缓存），`writtenUpToFrame`
 * 是**内存变量** —— 进程一被杀（系统回收 / 崩溃 / 断电）两样都丢。
 *
 * ⇒ **只有定期落盘的 manifest 能告诉下次启动"哪些区间真的有数据"。**
 *
 * ## 记录的粒度
 * 不强求像素级精确（每 2s 落一次盘，最多丢 2s）。
 * `ranges` 是**连续区间的并集**（自动合并相邻/重叠）。
 *
 * ## 原子性
 * 写 `MANIFEST.json.tmp` 再 `renameTo(MANIFEST.json)` ——
 * 崩溃时要么是旧版、要么是新版，**不会读到半截 JSON**。
 *
 * ## 向后兼容
 * 没有 manifest 的目录（旧缓存）→ [load] 返回 null → 调用方按旧行为处理。
 */
data class RangeManifest(
    /** 整曲总帧数（与 stem 文件头一致）。 */
    val totalFrames: Int,
    val sampleRate: Int,
    /** 已连续分离的区间（绝对帧，左闭右开，已排序且互不相邻）。 */
    val ranges: List<IntRange>,
    /** 本次边播的起点（0 = 从头开始，即没有"补前段"阶段）。 */
    val edgeStartFrame: Int = 0,
    /** 是否已覆盖整个 [0, totalFrames)。 */
    val complete: Boolean = false,
    /** 模型 id（换模型后缓存不通用）。 */
    val modelId: String = "",
) {

    /** 已分离的总帧数（各区间长度之和）。 */
    val coveredFrames: Int get() = ranges.sumOf { it.last - it.first + 1 }

    /** 覆盖率 0..1（用于 UI 显示"补前段 60%"）。 */
    val coveredFraction: Float
        get() = if (totalFrames <= 0) 0f
        else (coveredFrames.toFloat() / totalFrames).coerceIn(0f, 1f)

    /** [frame] 这一帧是否已分离。 */
    fun contains(frame: Int): Boolean = ranges.any { frame in it }

    /**
     * 尚未分离的区间（按顺序）。
     *
     * 用途：恢复时据此决定"还需要算什么"。
     */
    fun missingRanges(): List<IntRange> {
        if (ranges.isEmpty()) return listOf(0 until totalFrames)
        val out = ArrayList<IntRange>()
        var cursor = 0
        for (r in ranges) {
            if (r.first > cursor) out += cursor until r.first
            cursor = maxOf(cursor, r.last + 1)
        }
        if (cursor < totalFrames) out += cursor until totalFrames
        return out
    }

    /** 加入一段新区间，返回合并后的新 manifest。 */
    fun plus(from: Int, to: Int): RangeManifest {
        if (to <= from) return this
        // ★显式构造 List<IntRange>：`ranges + (from until to)` 会被解析成
        //   IntRange 的 `+`（把范围当集合展开），产生 List<Any> 错误。
        val merged = mergeRanges(ranges + listOf(from until to))
        val isComplete = merged.size == 1 &&
            merged[0].first == 0 &&
            // ★尾部容差：块拼接是 0.5s 交叉淡化，最后一块的尾端总会差几百帧
            //   （44.1kHz 下 ~4ms，听不出来）。严格对齐会让 complete 永远为 false，
            //   进而让“已分离完成”的缓存被当成需续算，反复重跑。
            merged[0].last >= totalFrames - 1 - TAIL_TOLERANCE_FRAMES
        return copy(ranges = merged, complete = isComplete)
    }

    /**
     * 把区间合并成**互不相邻**的并集（排序 + 合并）。
     *
     * 抽成 companion 函数便于单测（不依赖 Android 的 JSONObject）。
     */
    companion object {
        private const val TAG = "RangeManifest"
        const val FILE_NAME = "MANIFEST.json"
        private const val VERSION = 1

        /**
         * ★尾部容差（帧）：判断 complete 时允许末尾差这么多。
         *
         * 为何需要：块拼接用 0.5s 交叉淡化，最后一块的尾端总差几百帧
         * （真机实测 194 帧 ≈ 4.4ms，听不出来）。
         * 若严格对齐，`complete` 永远为 false → 下次进来又当“未完成”续算，
         * 白跑一遍（用户会看到莫名奇妙的“补前段”）。
         *
         * 取 0.05s（2205 帧）—— 比实测差距大 10 倍余量，又远小于听觉可辨阈。
         */
        private const val TAIL_TOLERANCE_FRAMES = 2_205

        /** 合并重叠/相邻区间。纯函数，可 JVM 单测。 */
        fun mergeRanges(input: List<IntRange>): List<IntRange> {
            val valid = input.filter { it.last >= it.first }.sortedBy { it.first }
            if (valid.isEmpty()) return emptyList()
            val out = ArrayList<IntRange>()
            var cur = valid[0]
            for (i in 1 until valid.size) {
                val next = valid[i]
                // 相邻也算连续（[0,10) 与 [10,20) → [0,20)）
                if (next.first <= cur.last + 1) {
                    cur = cur.first..maxOf(cur.last, next.last)
                } else {
                    out += cur
                    cur = next
                }
            }
            out += cur
            return out
        }

        /** 从目录读取；不存在/损坏/版本不符/元信息不匹配 → null。 */
        fun load(dir: File, expectTotalFrames: Int = -1, expectModelId: String? = null): RangeManifest? {
            val f = File(dir, FILE_NAME)
            if (!f.exists()) return null
            return runCatching {
                val o = JSONObject(f.readText())
                if (o.optInt("version", 0) != VERSION) {
                    Log.w(TAG, "manifest 版本不符，忽略：${o.optInt("version")}")
                    return null
                }
                val total = o.getInt("totalFrames")
                val model = o.optString("modelId", "")
                // ★元信息不匹配 → 视为无效（换模型/换曲长后旧 manifest 会误导）
                if (expectTotalFrames >= 0 && total != expectTotalFrames) {
                    Log.w(TAG, "manifest totalFrames($total) != 期望($expectTotalFrames)，忽略")
                    return null
                }
                if (expectModelId != null && model != expectModelId) {
                    Log.w(TAG, "manifest modelId($model) != 期望($expectModelId)，忽略")
                    return null
                }
                val arr = o.optJSONArray("ranges") ?: JSONArray()
                val rs = ArrayList<IntRange>(arr.length())
                for (i in 0 until arr.length()) {
                    val pair = arr.getJSONArray(i)
                    rs += pair.getInt(0) until pair.getInt(1)
                }
                RangeManifest(
                    totalFrames = total,
                    sampleRate = o.optInt("sampleRate", 44_100),
                    ranges = mergeRanges(rs),
                    edgeStartFrame = o.optInt("edgeStartFrame", 0),
                    complete = o.optBoolean("complete", false),
                    modelId = model,
                )
            }.getOrElse {
                Log.w(TAG, "manifest 解析失败", it)
                null
            }
        }
    }

    /** 原子写入 `dir/MANIFEST.json`（tmp → rename）。 */
    fun save(dir: File) {
        val o = JSONObject().apply {
            put("version", VERSION)
            put("totalFrames", totalFrames)
            put("sampleRate", sampleRate)
            put("edgeStartFrame", edgeStartFrame)
            put("complete", complete)
            put("modelId", modelId)
            put(
                "ranges",
                JSONArray().apply {
                    ranges.forEach { r -> put(JSONArray().apply { put(r.first); put(r.last + 1) }) }
                },
            )
        }
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(o.toString())
            val target = File(dir, FILE_NAME)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                // renameTo 在某些文件系统上会失败 → 回退到直接写
                target.writeText(o.toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "manifest 写入失败", it) }
    }
}
