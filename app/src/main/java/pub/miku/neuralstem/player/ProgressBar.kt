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

    // ★ 平滑位置：锚点外推 + 缓追（见 SmoothPosition.kt）→ 时间数字不卡不跳。
    //   resetKey = 时长：切歌时重置（避免旧歌状态带入）。
    val smoothPos = rememberSmoothPosition(
        anchorFlow,
        resetKey = progressState.value.durationMs,
    )

    // ★ 每帧失效：使时间数字平滑跟手（不再 10Hz 跳）。
    var frameTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameTick++ }
        }
    }
    frameTick  // 读一下 → 每帧重绘

    // ★ 位置用平滑值；拖动中则用手指位置。
    val durationMs = progressState.value.durationMs
    val realPositionMs = if (smoothPos.value > 0f) {
        smoothPos.value.toLong()
    } else {
        progressState.value.positionMs
    }
    val progress = if (dragging) dragProgress
    else if (durationMs > 0) (realPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
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
                    size = Size(size.width * progress, barH),
                    cornerRadius = CornerRadius(barH / 2f),
                )
                drawCircle(color = playedColor, radius = 10f, center = Offset(size.width * progress, cy))
            }
        }
    }
}
