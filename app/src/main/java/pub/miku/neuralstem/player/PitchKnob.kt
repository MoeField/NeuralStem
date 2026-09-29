package pub.miku.neuralstem.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pub.miku.neuralstem.ui.theme.WavePlayed
import pub.miku.neuralstem.ui.theme.WaveRemaining
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 阶段 UI-V：**变调旋钮**（DJ 风格）。
 *
 * - 中间 = 0；逆时针到 -12 半音、顺时针到 +12
 * - 拖动：**垂直拖动**改变值（向上增、向下减），每 <stepPx> 像素 1 半音
 *   （比“旋转拖动”更好操作，也更适合手指）
 * - 角度范围约 -135°..+135°（指针随值转动）
 *
 * @param semitones 当前半音（-12..12）
 * @param range 最大半音数（默认 12）
 */
@Composable
fun PitchKnob(
    semitones: Float,
    onPitchChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: Int = 12,
    knobSize: Dp = 96.dp,
) {
    var dragging by remember { mutableFloatStateOf(semitones) }
    var accumulating by remember { mutableStateOf(false) }
    var acc by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(knobSize)
                .pointerInput(range) {
                    detectDragGestures(
                        onDragStart = { accumulating = true; acc = 0f; dragging = semitones },
                        onDragEnd = {
                            // ★ 松手才下发一次（拖动中只更新视觉）：
                            //   避免每个中间值都发 PlaybackParameters → Sonic/AudioTrack 频繁重配
                            //   → 某些设备 AudioTrack 重配超时 → ERROR_CODE_TIMEOUT → “没声音”。
                            accumulating = false
                            if (dragging != semitones) onPitchChange(dragging)
                        },
                        onDragCancel = { accumulating = false },
                        onDrag = { _, drag ->
                            // 仅累积 + 更新本地视觉值（**不下发**）
                            acc += -drag.y
                            val stepPx = 10f   // 每 10px 1 半音（旋钮变小 → 灵敏度略升）
                            dragging = (acc / stepPx).roundToInt().toFloat()
                                .coerceIn(-range.toFloat(), range.toFloat())
                        },
                    )
                },
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val c = Offset(cx, cy)
            val r = minOf(cx, cy) * 0.86f
            val value = if (accumulating) dragging else semitones
            val frac = (value / range).coerceIn(-1f, 1f)

            // 底盘
            drawCircle(WaveRemaining, r, c)
            drawCircle(WavePlayed, r, c, style = Stroke(width = 3f))

            // 指针：从中心指向 -90°(向上) ± 135°*frac
            val ang = Math.toRadians((-90 + frac * 135).toDouble())
            drawLine(
                color = WavePlayed,
                start = c,
                end = Offset(cx + cos(ang).toFloat() * r * 0.85f, cy + sin(ang).toFloat() * r * 0.85f),
                strokeWidth = 5f,
            )
            drawCircle(WavePlayed, r * 0.16f, c)
        }
        Text(
            "%+.0f".format(if (accumulating) dragging else semitones) + " st",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
