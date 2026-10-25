package pub.miku.neuralstem.experimental.ncm

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * ★把**解密后的 NCM 音频字节**伪装成一个 Media3 DataSource，交给 ExoPlayer 播放。
 *
 * ## 为什么需要它
 * NCM 解密是「整文件读入 → AES 解密钥 → 循环 XOR 得音频」，结果在**内存**里而不是文件。
 * ExoPlayer 需要从内存读。
 *
 * ## ★ 必须继承 [BaseDataSource]（2026-10-25 真机踩到的坑）
 *
 * 第一版直接 `implements DataSource`，结果：
 * ```
 * NcmMemSrc: read#1 len=3 ... read#5 len=8   ← 只读了 44 字节（正好 ftyp box 大小）
 * PlayerVm: STATE_ENDED                       ← 随后立即结束
 * ```
 * 排查发现 media3 自带的 `ByteArrayDataSource` 是 `extends BaseDataSource`。
 * 基类提供 `transferInitializing/transferStarted/bytesTransferred/transferEnded`
 * 这套统计回调；直接实现接口会跳过它们，导致上层的 loader 收不到“有数据进来”的信号
 * → 认为流已结束 → `STATE_ENDED`。
 *
 * ## 对照官方实现（`androidx/media3/datasource/ByteArrayDataSource.java`）
 * | 点 | 官方做法 | 本类 |
 * |---|---|---|
 * | `open()` 返回 | `dataSpec.length != UNSET ? dataSpec.length : bytesRemaining` | 同 |
 * | `read()` 结束 | `bytesRemaining == 0 → RESULT_END_OF_INPUT` | 同 |
 * | 每次读 | 调 `bytesTransferred(length)` | 同 |
 *
 * ## 与官方 `ByteArrayDataSource` 的区别
 * 官方要在构造时把整个 byte[] 塞进去；而本项目要把 uri 传给**另一个组件**
 * （MediaSessionService 里的 ExoPlayer），字节数组无法随 uri 走 →
 * 用「共享单例 [NcmPlaybackStore] + key 查表」。
 */
@UnstableApi
class NcmMemoryDataSource : BaseDataSource(/* isNetwork= */ false) {

    private var uri: Uri? = null
    private var data: ByteArray? = null
    private var readPosition: Int = 0
    private var bytesRemaining: Int = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val key = dataSpec.uri.host ?: dataSpec.uri.toString()
        val bytes = NcmPlaybackStore.get(key)
            ?: throw IOException("NcmMemoryDataSource: 无对应数据 $key（尚未解密或已被回收）")
        data = bytes

        if (dataSpec.position > bytes.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        readPosition = dataSpec.position.toInt()
        bytesRemaining = bytes.size - readPosition
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            bytesRemaining = minOf(bytesRemaining.toLong(), dataSpec.length).toInt()
        }
        opened = true
        transferStarted(dataSpec)
        val result = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            bytesRemaining.toLong()
        }
        Log.i(
            TAG,
            "open: key=$key pos=${dataSpec.position} specLen=${dataSpec.length} " +
                "remain=$bytesRemaining total=${bytes.size} -> return $result",
        )
        return result
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0) {
            if (readCount < 3) Log.i(TAG, "read EOF at pos=$readPosition")
            return C.RESULT_END_OF_INPUT
        }
        val n = minOf(length, bytesRemaining)
        System.arraycopy(data!!, readPosition, buffer, offset, n)
        readPosition += n
        bytesRemaining -= n
        bytesTransferred(n)
        readCount++
        if (readCount <= 5) {
            Log.i(TAG, "read#$readCount n=$n pos=$readPosition remain=$bytesRemaining")
        }
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
        data = null
        readPosition = 0
        bytesRemaining = 0
    }

    private var readCount = 0

    companion object {
        const val TAG = "NcmMemSrc"

        /** 自定义 scheme：`ncm://<key>`。 */
        const val SCHEME = "ncm"

        fun uriFor(key: String): Uri = Uri.parse("$SCHEME://$key")
    }
}

/**
 * ★NCM 解密结果的**进程级存储**（供 [NcmMemoryDataSource] 按 key 取）。
 *
 * `MediaItem` 会随 MediaSession 在各组件间传递，几十 MB 字节数组塞不进去 →
 * 用「共享单例 + key」。本项目 MediaSession 是同进程本地服务，单例可直传。
 *
 * 只保留最近 [MAX_ENTRIES] 条（LRU）。一首歌 15–40MB，2 条 ≈ 80MB 上限
 * （App 已开 `largeHeap`）。
 */
object NcmPlaybackStore {

    private const val TAG = "NcmStore"

    private const val MAX_ENTRIES = 2

    private val map = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ByteArray>?,
        ): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun put(key: String, bytes: ByteArray): String {
        map[key] = bytes
        Log.i(TAG, "put $key (${bytes.size / 1_048_576.0}MB), entries=${map.size}")
        return key
    }

    @Synchronized
    fun get(key: String): ByteArray? = map[key]

    @Synchronized
    fun contains(key: String): Boolean = map.containsKey(key)

    @Synchronized
    fun clear() {
        map.clear()
    }
}
