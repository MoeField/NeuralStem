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
 * ★seek 跳变阈值（ms）。
 *
 * 锚点位置突变超过它就**不做平滑补偿**，直接对齐。
 * 依据（实测）：拖进度条松手时，smooth 还在往前跑（207217ms），
 * 而 seek 后的真值已是 200900ms；若照常补偿，会先“多跑出 6 秒”再退回来，
 * 表现为“填充→变灰→再填”。
 *
 * 1000ms：远大于正常播放每帧推进（≤100ms），又小于任何有意义的 seek 距离。
 */
private const val SEEK_JUMP_MS = 1_000f

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
    /** 上一帧锚点的“含外推”位置；用来识别 seek 这种**位置突变**。 */
    var lastRaw = remember { 0f }

    LaunchedEffect(resetKey) {
        // 切歌：清掉旧歌的平滑状态
        pos.floatValue = 0f
        lastDisplay = 0f
        lastAnchorKey = Long.MIN_VALUE
        lastRaw = 0f
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
                // ★2026-10-26：记住锚点上一次的“含外推”位置，用来识别 seek 跳变。
                if (lastAnchorKey != Long.MIN_VALUE) {
                    val prevRaw = lastRaw
                    val jump = abs(raw - prevRaw)
                    /*
                     * ★★ 实测定位到的真根因（2026-10-26）：
                     *
                     * 拖进度条时用户看到的是 `dragProgress`（手指位置），
                     * 而 `SmoothPosition` 里的 `lastDisplay` 在此期间**仍在向前跑**
                     * （因为播放没停）。实测数据：
                     *   松手瞬间：手指=200900ms（0.7124），smooth=207217ms
                     *   松手后第1帧：raw 跳到 200900，但 delta = 207217-200900 = +6317
                     *   显示 = 200900 + 6317 = 207217  ← 先冲到 0.7314
                     *   然后 delta 衰减，慢慢掉回 0.7124
                     * 这就是用户看到的“填充→变灰→再填”。
                     *
                     * ⇒ seek 这类**位置突变**，不能做平滑补偿：
                     *   直接对齐到新位置，否则会先“多跑出一截”再退回来。
                     *
                     * 阈值 1000ms：远大于正常播放每帧推进（≤100ms），
                     * 又小于任何有意义的 seek 距离。
                     */
                    if (jump > SEEK_JUMP_MS) {
                        delta = 0f
                        lastDisplay = raw
                        lastRaw = raw
                        lastAnchorKey = key
                        pos.floatValue = raw
                        return@withFrameNanos
                    }
                }
                if (first || key != lastAnchorKey) {
                    if (first) {
                        delta = 0f
                    } else {
                        val capture = lastDisplay - raw
                        // ★ 短窗口内（COOLDOWN）的多次捕获**替换**而非累加：
                        //   重锚往往连续几帧都触发；累加会过度补偿（回弹）。
                        val withinCooldown = (nowNs - lastCaptureNs) < CAPTURE_COOLDOWN_NS
                        delta = if (withinCooldown) capture else delta + capture
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
                lastRaw = raw
            }
        }
    }
    return pos
}
