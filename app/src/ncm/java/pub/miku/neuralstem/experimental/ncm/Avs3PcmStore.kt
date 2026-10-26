package pub.miku.neuralstem.experimental.ncm

import android.util.Log

/**
 * ★AVS3 解码产物（立体声 PCM）的进程内暂存区。
 *
 * ## 为什么需要它
 * ExoPlayer 跑在 `MediaSessionService` 里，而解码发生在 `MainActivity`（或 ViewModel）。
 * 两者之间只能靠 **uri 字符串**传递，传不了 `ByteArray`。
 *
 * 所以做法是：解码完把 PCM 放进本 Store，发一个 `avs3pcm://<key>` 的 uri 给播放器，
 * 播放器的 `DataSource` 再用 key 回来取数据。
 * （`NcmPlaybackStore` 用的是同一套思路，只是存的是解密的容器字节。）
 *
 * ## ★为什么只留 2 条
 * 一首 2 分钟的立体声 PCM16@44.1k 约 **23MB**。
 * 留 2 条 = 最多 46MB，切歌时还能命中上一首。
 * 再多就有 OOM 风险（尤其低端机）。用 LRU 自动淘汰最久没用的。
 *
 * ## 线程安全
 * 解码在后台线程写、播放器在主线程/播放线程读，全部方法 `@Synchronized`。
 *
 * ## 生命周期
 * 只在**本进程存活期间**有效。App 被杀后 uri 就失效了 ——
 * 这是可接受的：AVS3 曲目本来就是「打开时现解」，不是持久化缓存。
 */
object Avs3PcmStore {

    private const val TAG = "Avs3PcmStore"

    /** 最多同时保留几条（LRU）。 */
    private const val MAX_ENTRIES = 2

    /** 一条解码结果。 */
    data class Entry(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        /** 原始时长（毫秒），仅用于日志/UI。 */
        val durationMs: Long,
    ) {
        /** 数据体积（MB），用于日志。 */
        val sizeMb: Double get() = pcm.size / 1_048_576.0
    }

    private val map = object : LinkedHashMap<String, Entry>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            val evict = size > MAX_ENTRIES
            if (evict && eldest != null) {
                Log.i(TAG, "LRU 淘汰 ${eldest.key}（${"%.1f".format(eldest.value.sizeMb)}MB）")
            }
            return evict
        }
    }

    /**
     * 存入一条解码结果，返回要用的 key。
     *
     * key 由调用方给（通常是文件路径或名字的哈希），便于重复打开同一首时复用。
     */
    @Synchronized
    fun put(key: String, entry: Entry): String {
        map[key] = entry
        Log.i(
            TAG,
            "put $key（${"%.1f".format(entry.sizeMb)}MB，" +
                "${entry.channels}ch@${entry.sampleRate}Hz，" +
                "${entry.durationMs / 1000}s），entries=${map.size}",
        )
        return key
    }

    @Synchronized
    fun get(key: String): Entry? = map[key]

    @Synchronized
    fun contains(key: String): Boolean = map.containsKey(key)

    @Synchronized
    fun remove(key: String) {
        map.remove(key)
    }

    @Synchronized
    fun clear() {
        Log.i(TAG, "clear（释放 ${"%.1f".format(map.values.sumOf { it.sizeMb })}MB）")
        map.clear()
    }

    /** 当前占用（MB），用于 UI/诊断。 */
    @Synchronized
    fun usedMb(): Double = map.values.sumOf { it.sizeMb }
}
