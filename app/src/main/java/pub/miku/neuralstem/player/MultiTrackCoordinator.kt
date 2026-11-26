package pub.miku.neuralstem.player

import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * v0.3.1 Step 3a：多轨会话状态宿主。
 *
 * ## 线程归属（必须遵守）
 * - [current] / [active]：**主线程专属**。命令 handler、media listener、快照 lambda 读写。
 * - [io]：唯一跨线程执行者，只跑 `load/loadStream/close`；完成回填 `current/active`
 *   必须在 `Dispatchers.Main.immediate` 上做。
 * - **不要**在 [io] 线程直接读写 [current]/[active]，否则重新引入跨线程可见性问题
 *   （P1-1/P1-B 回归）。
 *
 * 3a 只做字段搬家：load/exit/音量/焦点方法仍在 `PlaybackService`，通过 alias 访问；
 * 3b/3c 再迁方法并删除 alias。
 */
internal class MultiTrackCoordinator(
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
}
