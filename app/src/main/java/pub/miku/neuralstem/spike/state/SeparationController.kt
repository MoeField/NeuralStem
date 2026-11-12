package pub.miku.neuralstem.spike.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 分离领域单写者（M2）。
 *
 * - 所有事件经一个 Channel 串行进入 reducer；
 * - 只有此 actor 写 [snapshot]；
 * - Effect 交给 [onEffect]，Effect 执行结果必须再以 Event 回来。
 *
 * 约定：[onEffect] 不能阻塞/直接改 Snapshot；生产实现应把耗时工作丢到自己的协程，
 * 完成后调用 [send] 发 Domain 事件。
 */
class SeparationController(
    private val scope: CoroutineScope,
    initial: SeparationSnapshot = SeparationSnapshot(),
    private val onEffect: (SeparationEffect) -> Unit,
) {
    private val _snapshot = MutableStateFlow(initial)
    val snapshot: StateFlow<SeparationSnapshot> = _snapshot.asStateFlow()

    private val events = Channel<SeparationEvent>(Channel.UNLIMITED)
    private val actor: Job = scope.launch {
        for (event in events) {
            val result = SeparationReducer.reduce(_snapshot.value, event)
            _snapshot.value = result.snapshot
            for (effect in result.effects) {
                // Effect 执行失败不应杀死状态机；记录由调用方处理。
                runCatching { onEffect(effect) }
            }
        }
    }

    /** 非阻塞入队；事件顺序由 Channel 保证。 */
    fun send(event: SeparationEvent): Boolean = events.trySend(event).isSuccess

    fun start(request: StartRequest): Boolean =
        send(SeparationEvent.User.Start(request))

    fun cancel(jobId: Long? = null): Boolean =
        send(SeparationEvent.User.Cancel(jobId))

    fun attach(jobId: Long? = null): Boolean =
        send(SeparationEvent.User.Attach(jobId))

    fun detach(): Boolean = send(SeparationEvent.User.Detach)

    fun close() {
        events.close()
        actor.cancel()
    }
}
