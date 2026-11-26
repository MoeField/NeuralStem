package pub.miku.neuralstem.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.3.1 Step 3：多轨会话状态 + 加载编排。
 *
 * ## 线程归属（必须遵守）
 * - [current] / [active]：**主线程专属**。命令 handler、media listener、快照 lambda 读写。
 * - [io]：唯一跨线程执行者，只跑 `load/loadStream/close`；完成回填 `current/active`
 *   必须在 `Dispatchers.Main.immediate` 上做。
 * - **不要**在 [io] 线程直接读写 [current]/[active]，否则重新引入跨线程可见性问题
 *   （P1-1/P1-B 回归）。
 *
 * ## 依赖方向（避免环）
 * 本类**不持有** `SnapshotPublisher`/`EqHost`；需要时通过注入回调
 * （[requestSnapshot] 由 Service 指向 `snapshotPublisher.request()`）。
 * Service 的装配顺序是单向的：Coordinator 只回调 Service，不反向持有装配对象。
 *
 * 3a 只搬字段；3b 迁 `ensure/loadDisk/loadStream/abort`；3c 迁 exit/音量/焦点并删 alias。
 */
internal class MultiTrackCoordinator(
    private val context: Context,
    private val onAudioSessionChanged: (Int) -> Unit,
    private val getOriginalMeta: () -> Pair<String?, String?>,
    private val getSpeedPitch: () -> Pair<Float, Float>,
    /** 加载成功后的收尾编排（主线程）；由 Service 注入（音量/播放/焦点/EQ/进度/快照）。 */
    private val onLoaded: (MultiTrackController, Long, String) -> Unit,
    /** 加载失败/取消后的清场（主线程）；由 Service 注入。 */
    private val onAborted: () -> Unit,
    /** 退出多轨后的收尾（焦点/进度/EQ/门面/快照，主线程）；由 Service 注入。 */
    private val onExitCleanup: () -> Unit,
    onFocusChange: (Int) -> Unit,
) {
    /** 当前多轨控制器；主线程读写。 */
    @Volatile var current: MultiTrackController? = null

    /** 是否处于多轨模式；主线程读写。 */
    @Volatile var active: Boolean = false

    /** 多轨 load/close 专用单线程 IO（唯一跨线程执行者）。 */
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    var loadJob: Job? = null
    var loadPending: Boolean = false
    var loadGen: Long = 0L

    /** 多轨当前曲的原始 uri/title（VM 重建恢复用）。 */
    var originalUri: String? = null
    var originalTitle: String? = null

    /** 多轨音频焦点（A3）：Service 统一持有。 */
    var audioAttributes: AudioAttributes? = null
    var focusHeld: Boolean = false
    var focusWasPlaying: Boolean = false

    val focusListener: AudioManager.OnAudioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(change) }

    /** 确保 controller 存在（主线程）。 */
    fun ensure(): MultiTrackController {
        current?.let { return it }
        val c = MultiTrackController(context) { sessionId ->
            onAudioSessionChanged(sessionId)
        }
        // P2-4：不再向 controller 注册永不派发的 listener（控制器不是 ExoPlayer）。
        current = c
        return c
    }

    /** 加载磁盘 WAV 多轨。@return null=已受理；非 null=错误消息。 */
    fun loadDisk(
        items: List<Triple<String, String, Uri>>,
        startMs: Long,
        title: String?,
    ): String? = startLoad(
        itemCount = items.size,
        startMs = startMs,
        logMsg = "loadMultiTrack(mix): ${items.size} track(s) ids=${items.map { it.first }} " +
            "（singlePlayer 静音当门面，实际声音来自 StemMixer）",
        loadOnIo = { c -> c.load(items, title) },
    )

    /** 加载流式多轨（边分离边播）。@return null=已受理；非 null=错误消息。 */
    fun loadStream(
        items: List<MultiTrackController.StreamItem>,
        startMs: Long,
        title: String?,
    ): String? = startLoad(
        itemCount = items.size,
        startMs = startMs,
        logMsg = "loadStream: ${items.size} streaming track(s) → StemMixer",
        loadOnIo = { c -> c.loadStream(items, title) },
    )

    private fun startLoad(
        itemCount: Int,
        startMs: Long,
        logMsg: String,
        loadOnIo: (MultiTrackController) -> Unit,
    ): String? {
        val c = ensure()
        // 旧 mixer 先暂停，避免 IO 等待 join 期间还在出声。
        runCatching { current?.pause() }
        val gen = ++loadGen
        loadPending = true
        val (uri, t) = getOriginalMeta()
        originalUri = uri
        originalTitle = t
        val (speed, pitchRate) = getSpeedPitch()
        loadJob = io.launch {
            val loaded = runCatching { loadOnIo(c) }
                .onFailure { Log.e(TAG, "multi load failed on io", it) }
                .isSuccess
            withContext(Dispatchers.Main.immediate) {
                if (gen != loadGen) return@withContext
                loadPending = false
                if (!loaded || !c.isReady()) {
                    abort(c)
                    return@withContext
                }
                // ★把单轨期间/上次多轨的变速变调带给新混音器。
                c.setSpeedPitch(speed, pitchRate)
                onLoaded(c, startMs, logMsg)
            }
        }
        return null
    }

    /** 门面 seek → 混音器 seek（多轨只有一个 framePos，所有 stem 自然同步）。 */
    fun seekTo(positionMs: Long) {
        current?.seekTo(positionMs)
    }

    /** @return false = 非多轨。 */
    fun setVolume(trackId: String, volume: Float): Boolean {
        val c = current ?: return false
        c.trackList.firstOrNull { it.id == trackId }?.volume = volume
        c.refreshVolumes()
        return true
    }

    /** @return false = 非多轨。 */
    fun toggleMute(trackId: String): Boolean {
        val c = current ?: return false
        c.trackList.firstOrNull { it.id == trackId }?.let { it.muted = !it.muted }
        c.refreshVolumes()
        return true
    }

    /** @return false = 非多轨。 */
    fun toggleSolo(trackId: String): Boolean {
        val c = current ?: return false
        c.toggleSolo(trackId)
        return true
    }

    /**
     * 退出多轨：清状态 + 异步 close，然后由 Service 收尾（焦点/进度/EQ/门面/快照）。
     * 注意顺序：先 pause 再置空，避免关闭期间还在出声。
     */
    fun exit() {
        active = false
        loadGen++                 // 使在途 load 的发布失效
        loadPending = false
        originalUri = null
        originalTitle = null
        val closing = current
        runCatching { closing?.pause() }
        current = null
        if (closing != null) io.launch { runCatching { closing.close() } }
        onExitCleanup()
    }

    /** 加载失败/取消：清状态 + 异步 close（join 可能在 IO 上耗时）。 */
    fun abort(c: MultiTrackController) {
        io.launch { runCatching { c.close() } }
        if (current === c) current = null
        if (active) active = false
        onAborted()
    }

    companion object {
        private const val TAG = "MultiTrackCoordinator"
    }
}
