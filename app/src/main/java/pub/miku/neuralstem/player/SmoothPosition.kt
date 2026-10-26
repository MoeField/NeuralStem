package pub.miku.neuralstem.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import android.os.SystemClock
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * 由 [PlayerViewModel.UiClock] 锚点得到**每帧平滑**的播放位置（ms）。
 *
 * ## 为何需要
 * 1. `currentPosition` 只有 ~10Hz 且**刷新不均匀**（实测滞留可达 ~300-400ms）——直接驱动 UI 会“卡/跳”。
 * 2. VM 的锚点已做“本地时钟外推”；但**重锚**（真实位置突变/启动期大漂移）仍会造成**一次性回弹**。
 *
 * ⇒ 本函数在每帧：`display = anchor.extrapolate(now) + smoothDelta`；
 *   当锚点变化导致显示值出现突变时，把突变计入 `smoothDelta`，再**逐帧衰减**，
 *   从而把“回弹”摊成 ~0.4s 的平滑过渡（无论锚点怎么变都不跳）。
 *
 * 仅在 draw 阶段读返回值（配合 `withFrameNanos` 每帧重绘）→ 只重绘、不重组。
 */
/** 短窗口内多次“捕获”视为同一次重锚 → 替换而非累加（避免过度补偿→回弹）。 */
private const val CAPTURE_COOLDOWN_NS = 300_000_000L   // 300ms

/**
 * ★跳变阈值（ms）：锚点位置变化超过它就不做平滑补偿，直接跟过去。
 *
 * 为何 1500ms：
 * - 远大于正常抖动（实测 ~300ms）
 * - 小于用户能感知为“拖动”的最小距离
 * 没有这条时，拖进度条会“先到目标、又回弹到旧位置、再冲回去”（实测现象）。
 */
private const val JUMP_THRESHOLD_MS = 1_500f

@Composable
fun rememberSmoothPosition(
    anchorFlow: StateFlow<PlayerViewModel.UiClock?>,
    /** 切歌/重导入时变化的值（如 曲名+时长）；变化则**重置内部状态**，
     *  否则旧歌的 delta/lastDisplay 会带入新歌 → 波形/进度显示错乱（“拖一下进度条就好”）。 */
    resetKey: Any? = Unit,
    smoothingPerFrame: Float = 0.85f,
): State<Float> {
    val anchorState = anchorFlow.collectAsState()
    val pos = remember { mutableFloatStateOf(0f) }
    var lastDisplay = remember { 0f }
    var lastAnchorKey = remember { Long.MIN_VALUE }

    LaunchedEffect(resetKey) {
        // 切歌：清掉旧歌的平滑状态
        pos.floatValue = 0f
        lastDisplay = 0f
        lastAnchorKey = Long.MIN_VALUE
        var delta = 0f
        var first = true
        var lastCaptureNs = 0L
        while (true) {
            withFrameNanos { nowNs ->
                val a = anchorState.value
                if (a == null) {
                    pos.floatValue = 0f
                    return@withFrameNanos
                }
                val raw = a.extrapolate(SystemClock.elapsedRealtime()).toFloat()
                // ★ key 只认“实质变化”：positionMs / 播放态 / 速度。
                //   ⚠ 不能含 realtimeMs —— 锚点每 100ms 轮询都会刷新它，
                //     否则会“每 100ms 就误判为锚点跳变” → 反复吞 delta → 回弹/迟滞。
                val key = a.positionMs * 31 + a.speed.toRawBits().toLong() * 7 +
                    (if (a.isPlaying) 1L else 0L)
                if (first || key != lastAnchorKey) {
                    if (first) {
                        delta = 0f
                    } else {
                        val capture = lastDisplay - raw
                        /*
                         * ★2026-10-26 修正：**大跳变不补偿**。
                         *
                         * 本类的初衷是“摊平抖动”（~300ms 级别的锚点微调）。
                         * 但用户拖进度条是 **几秒到几分钟** 的跳变 ——
                         * 若也走补偿逻辑：
                         *     capture = 旧显示(10s) - 新锚点(60s) = -50s
                         *     delta = -50s  →  显示 60s + (-50s) = **10s**
                         * 然后再逐帧衰减爬回 60s。
                         * 这就是用户看到的“绿条先到点击处，又回弹到旧位置，再冲回去”。
                         *
                         * 阈值取 1500ms：远大于正常抖动（实测 ~300ms），
                         * 又小于用户能感知为“拖动”的最小距离。
                         */
                        val isJump = abs(capture) > JUMP_THRESHOLD_MS
                        if (isJump) {
                            // 跳变：立即跟随，不做平滑过渡
                            delta = 0f
                        } else {
                            // ★ 短窗口内的多次捕获**替换**而非累加：
                            //   重锚往往连续几帧都触发；累加会过度补偿（回弹）。
                            val withinCooldown =
                                (nowNs - lastCaptureNs) < CAPTURE_COOLDOWN_NS
                            delta = if (withinCooldown) capture else delta + capture
                        }
                        lastCaptureNs = nowNs
                    }
                    lastAnchorKey = key
                    first = false
                }
                // 把 delta 逐帧“吃掉”
                delta *= smoothingPerFrame
                if (abs(delta) < 0.5f) delta = 0f
                val disp = raw + delta
                pos.floatValue = disp
                lastDisplay = disp
            }
        }
    }
    return pos
}
