package pub.miku.neuralstem.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import pub.miku.neuralstem.ui.theme.WavePlayed
import pub.miku.neuralstem.ui.theme.WaveRemaining

/**
 * 阶段 UI 重做 · R1+：**横向进度条**（主 seek 入口）。
 *
 * ## 交互（用户定稿）
 * - **按下/拖动**：**不 seek**，只移动手指指示 + 在上方显示**目标时间气泡**。
 * - **松手**：才真正 `onSeek` → “松手后生效跳转”。
 * - 点（按下即抬起）也走同一逻辑 → 只有一次 seek，**不闪**。
 *
 * ## 实现
 * 用 `awaitEachGesture` 手动处理 down/move/up：拖动中只改本地 `dragProgress`，
 * 不触发任何外部状态变化；up 时才调用一次 `onSeek`。
 */
@Composable
fun ProgressBar(
    progressFlow: StateFlow<PlayerViewModel.Progress>,
    anchorFlow: StateFlow<PlayerViewModel.UiClock?>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    playedColor: Color = WavePlayed,
    remainingColor: Color = WaveRemaining,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    // ★ 进度在**本组件内** collect（重组范围仅限进度条），不影响整个播放页。
    val progressState = progressFlow.collectAsState()
    // 播放态从锚点流取（`Progress` 无此字段）；仅用于逐帧失效的守卫。
    val isPlaying = anchorFlow.collectAsState().value?.isPlaying == true

    // ★ 平滑位置：锚点外推 + 缓追（见 SmoothPosition.kt）→ 时间数字不卡不跳。
    //   resetKey = 时长：切歌时重置（避免旧歌状态带入）。
    //
    // ★2026-10-26：拖动时把**手指位置**传进去，让 smoothPos 锁定在那里。
    //   否则播放不停 → smoothPos 会在拖动期间继续向前跑，
    //   松手后补偿会“多跑一段再退回来”（实测：先冲高 6 秒再回落）。
    //   锁定后：松手瞬间 lastDisplay 就在手指位置，既能平滑过渡又不冲高。
    val smoothPos = rememberSmoothPosition(
        anchorFlow,
        resetKey = progressState.value.durationMs,
        dragMs = if (dragging && progressState.value.durationMs > 0) {
            dragProgress * progressState.value.durationMs
        } else {
            null
        },
    )

    // ★ 每帧失效（驱动连续重绘）；**仅在播放/拖动时**才逐帧触发。
    //   ⚠️ 修（2026-10-17）：原实现无条件 `frameTick++` 且在 **composition 阶段**读，
    //      → 每帧触发【重组】，静止时也不停 → Compose 永不 idle（仪器化测试超时）。
    //      照搬 DeckView：读 `frameTick` / `smoothPos` **只在 draw 阶段**（见下方 Canvas），
    //      并从 composition 移出；`isPlaying` 守卫防静止空转。
    var frameTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(isPlaying) {
        while (true) {
            withFrameNanos { if (isPlaying) frameTick++ }
        }
    }

    // ★ 时间数字用的（低频）进度：直接读 10Hz 的 `progressState`（不在 composition 读 `smoothPos`，
    //   否则每帧重组）。拖动中则用手指位置；播放中的平滑由下方 Canvas 的 draw 阶段负责。
    val durationMs = progressState.value.durationMs
    val progress = if (dragging) dragProgress
    else if (durationMs > 0) (progressState.value.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val previewMs = (progress * durationMs).toLong()

    Box(modifier = modifier) {
        Column {
            // 时间：拖动时显示“目标时间”；否则显示 当前/总
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Text(
                    PlayerViewModel.formatTime(previewMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Box(Modifier.weight(1f))
                Text(
                    PlayerViewModel.formatTime(durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerInput(Unit) {
                        // 用 detectDragGestures：它会自动 consume 事件（避免被父级滚动/抽屉抢走）。
                        // 按下即开始“拖动”（即使未移动）；松手才 seek。
                        detectDragGestures(
                            onDragStart = { off ->
                                dragging = true
                                dragProgress = (off.x / size.width).coerceIn(0f, 1f)
                            },
                            onDrag = { change, _ ->
                                change.consume()   // ★ 独占手势
                                dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                dragging = false
                                if (durationMs > 0) onSeek((dragProgress * durationMs).toLong())
                            },
                            onDragCancel = { dragging = false },
                        )
                    },
            ) {
                // ★ 在 draw 阶段才读逐帧值（不在 composition 阶段）→ 只重绘、不重组。
                @Suppress("UNUSED_EXPRESSION")
                frameTick
                val drawProgress = if (dragging) {
                    dragProgress
                } else if (durationMs > 0) {
                    val pos = if (smoothPos.value > 0f) smoothPos.value.toLong()
                    else progressState.value.positionMs
                    (pos.toFloat() / durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val cy = size.height / 2f
                val barH = 6f
                drawRoundRect(
                    color = remainingColor,
                    topLeft = Offset(0f, cy - barH / 2f),
                    size = Size(size.width, barH),
                    cornerRadius = CornerRadius(barH / 2f),
                )
                drawRoundRect(
                    color = playedColor,
                    topLeft = Offset(0f, cy - barH / 2f),
                    size = Size(size.width * drawProgress, barH),
                    cornerRadius = CornerRadius(barH / 2f),
                )
                /*
                 * ★2026-10-26 用户反馈：“手柄圆圈看着像可点击的滑块，
                 * 但进度条其实**只响应拖动、不响应点击**”。
                 *
                 * 所以不用圆点（圆点 = 可抓取的手柄语义），改成：
                 *   · **竖条**（宽 4dp、高比轨道略高的胶囊）—— 像“播放头”而不是“按钮”
                 *   · 颜色与已播部分一致，拖动时略亮一点做操作反馈
                 *   · 不做大的半径→ 视觉上不会让人去“点”它
                 *
                 * 若将来真的支持点击跳转，再换回圆点即可（圆点更符合“可点”语义）。
                 */
                val headW = 4f
                val headH = barH + 10f
                drawRoundRect(
                    color = if (dragging) playedColor else playedColor.copy(alpha = 0.85f),
                    topLeft = Offset(
                        (size.width * drawProgress - headW / 2f)
                            .coerceIn(0f, size.width - headW),
                        cy - headH / 2f,
                    ),
                    size = Size(headW, headH),
                    cornerRadius = CornerRadius(headW / 2f),
                )
            }
        }
    }
}
