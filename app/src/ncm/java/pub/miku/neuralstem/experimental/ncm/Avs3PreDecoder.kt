package pub.miku.neuralstem.experimental.ncm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ★播放时的**后台预解码**：把队列里后面几首提前解好，避免自动切歌时静音等待。
 *
 * ## 解决什么
 *
 * 自动切歌（播完 A 播 B）也算「用户意图的延续」，必须解码。但如果 B 没缓存，
 * 会**静音 42 秒**再出声 —— 用户什么都没做却突然没声音，是很差的体验。
 *
 * 所以播放 A 的时候，就把 A 后面的 N 首（N = 设置里的「预解码数量」，默认 1）
 * 在后台解掉。
 *
 * ## ★必须可取消（这是设计核心）
 * 用户可能：
 * - 直接跳到队列很后面 → 之前排队的预解码任务全部作废
 * - 关掉 App / 切歌 → 正在跑的解码应立即中止
 *
 * 如果不取消，会出现「用户已经切歌，后台还在解上一首」的白耗 CPU，
 * 甚至会跟**当前播放**的解码抢 CPU，让用户等更久。
 *
 * 实现上用一个 [Job]，每次调度新任务前先 `cancelAndJoin` 旧的。
 *
 * ## 优先级
 * 预解码的优先级**永远低于**当前播放的解码：
 * - 用 [Dispatchers.IO]，与主解码同一个线程池，但**只在播放稳定后才启动**
 * - 解码器内部每帧检查 `isCancelled`，能在几十毫秒内响应取消
 *
 * ## 不做的事
 * - 不预解码**当前正在播**那首（那是播放流程自己的事）
 * - 不预解码非 AVS3 文件（flac/mp3 本来就秒开，没有预热价值）
 */
object Avs3PreDecoder {

    private const val TAG = "Avs3PreDecoder"

    /** 专用 scope（SupervisorJob：一个任务失败不影响其他）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前在跑的预解码任务；调度新任务前会取消它。 */
    private var job: Job? = null

    /**
     * 调度预解码。
     *
     * @param context 用于取缓存目录与设置
     * @param queue 播放队列（按顺序，全部是**源文件路径**）
     * @param currentIndex 当前正在播的索引
     * @param count 预解码几首（0 = 关闭）
     * @param isAvs3 判断某个源文件是否需要解码（非 AVS3 直接跳过）
     * @param cacheKeyFor 取源文件的缓存键（拿不到返回 null → 跳过该首）
     * @param onProgress （可选）进度回调，给 UI 显示「正在预解码 2/3」
     */
    fun schedule(
        context: Context,
        queue: List<String>,
        currentIndex: Int,
        count: Int,
        isAvs3: (String) -> Boolean,
        cacheKeyFor: (String) -> String?,
        onProgress: ((done: Int, total: Int, name: String) -> Unit)? = null,
    ) {
        // 先取消上一批（用户可能刚切了歌）。
        cancel()

        if (count <= 0 || queue.isEmpty() || currentIndex < 0) {
            Log.i(TAG, "跳过预解码（count=$count, queue=${queue.size}）")
            return
        }

        // 取当前曲之后的 N 首（不环绕到开头 —— 那属于"播完整个队列"，
        // 用户大概率会手动干预，预热代价不值）。
        val targets = (1..count)
            .mapNotNull { offset -> queue.getOrNull(currentIndex + offset) }
            .filter { isAvs3(it) }

        if (targets.isEmpty()) {
            Log.i(TAG, "预解码目标为空（后面 ${count} 首都不是 AVS3 或没有更多）")
            return
        }

        job = scope.launch {
            val limitBytes = pub.miku.neuralstem.settings.SettingsRepository(
                context.applicationContext as android.app.Application,
            ).decodeCacheLimitBytes()

            for ((i, src) in targets.withIndex()) {
                if (!isActive) {
                    Log.i(TAG, "预解码已被取消（第 $i 首）")
                    return@launch
                }

                val name = File(src).name
                onProgress?.invoke(i, targets.size, name)

                val key = cacheKeyFor(src)
                if (key == null) {
                    Log.i(TAG, "跳过 $name：拿不到缓存键")
                    continue
                }
                if (Avs3PcmCache.get(context, key) != null) {
                    Log.i(TAG, "跳过 $name：已有缓存")
                    continue
                }

                val t0 = System.currentTimeMillis()
                val ok = decodeOne(context, src, key, limitBytes)
                Log.i(
                    TAG,
                    (if (ok) "✓" else "✗") + " 预解码 $name（${System.currentTimeMillis() - t0}ms）",
                )
            }
            onProgress?.invoke(targets.size, targets.size, "")
        }
    }

    /**
     * 解一首并写缓存。返回是否成功。
     *
     * ★这里的逻辑与 `avs3play` 命令里的那条链一致，但**不能复用 MainActivity 的反射代码**
     * （那是 devtools 调试路径）。这里直接调用 ncm 源集内的类 —— 因为
     * 本文件就在 ncm 源集里，可以直接引用。
     */
    private suspend fun decodeOne(
        context: Context,
        srcPath: String,
        cacheKey: String,
        limitBytes: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        var decoder: Avs3AudioDecoder? = null
        // ★取当前协程的 Job 来判断是否被取消。
        //   不能用 scope.isActive —— 那是整个调用域的状态：
        //   取消单个 job 时 scope 仍然活着，用它永远返回 true，
        //   于是“切歌后旧任务立即停止”就失效了。
        val self = coroutineContext[Job]
        try {
            // 1) 读源文件（私有目录，走 Shizuku）
            val bytes = NcmImporter.readBytesViaShizuku(srcPath) ?: return@withContext false

            // 2) 提取 MP4 音频样本
            val track = Mp4TrackExtractor.extractSamples(bytes) ?: return@withContext false

            // 3) 解码 + 下混
            decoder = Avs3AudioDecoder()
            if (!decoder.available) return@withContext false
            if (!decoder.open()) return@withContext false
            if (decoder.initSequence(track.samples[0]) != 0) return@withContext false

            val rate = decoder.sourceSampleRate().takeIf { it > 0 } ?: 44100
            val srcCh = decoder.sourceChannels().takeIf { it > 0 } ?: 12

            val result = Avs3PcmDecoder.decodeAll(
                decoder = decoder,
                frames = track.samples,
                headerBytes = 7,
                sampleRate = rate,
                sourceChannels = srcCh,
                downmix = Avs3PcmDecoder.Downmix.BS775,
                // ★取消检查：预解码必须能几十毫秒内响应取消，
                //   否则用户切歌后旧任务还会白烧 CPU 42 秒。
                isCancelled = { self?.isActive == false },
            ) ?: return@withContext false

            // 4) 写缓存
            val wav = Avs3WavWriter.toWavBytes(result.pcm, result.sampleRate, result.channels)
            Avs3PcmCache.put(context, cacheKey, wav, limitBytes) != null
        } catch (t: Throwable) {
            Log.w(TAG, "预解码失败 $srcPath", t)
            false
        } finally {
            runCatching { decoder?.close() }
        }
    }

    /** 取消所有预解码任务（切歌、退出播放、关闭设置项时调用）。 */
    fun cancel() {
        val j = job ?: return
        job = null
        scope.launch {
            runCatching { j.cancelAndJoin() }
            Log.i(TAG, "预解码任务已取消")
        }
    }

    /** 当前是否有任务在跑（UI 用）。 */
    fun isRunning(): Boolean = job?.isActive == true
}
