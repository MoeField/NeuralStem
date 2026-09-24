package pub.miku.neuralstem.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 波形组件(Task 2,guide F1.1–F1.3)。
 *
 * 绘制:每个包络点画一根竖线(min→max),居中对称;
 * 已播部分用 [playedColor],未播用 [unplayedColor](F1.3)。
 *
 * 拖动:按下手即 seek 到对应位置,拖动中持续 seek(F1.2 实时跳转);
 * 通过 [onSeek] 回调交给 VM,组件本身不持有播放器。
 *
 * **双轨预埋(Task 4)**:[waveforms] 是列表 —— 传入 1 条即单轨;
 * Task 4 传入 vocals+accompaniment 两条,自动用 [trackColors] 循环取色,半透明叠加。
 * 几何上两条轨使用同一时间轴,叠加绘制即可对齐。
 */
@Composable
fun WaveformView(
    waveforms: List<WaveformData?>,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    playedColor: Color = Color(0xFF6750A4),
    unplayedColor: Color = Color(0xFFCCC2DC),
    trackColors: List<Color> = listOf(playedColor, Color(0xFF7D5260)),
) {
    // 拖动中冻结回写:positionMs 轮询来的值会与手指位置打架,拖动期间以手指为准
    var dragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableStateOf(0L) }

    val effectivePosition = if (dragging) dragPositionMs else positionMs
    val progress = if (durationMs > 0) {
        (effectivePosition.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width * durationMs).toLong())
                }
            }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragPositionMs = (offset.x / size.width * durationMs).toLong()
                        onSeek(dragPositionMs)
                    },
                    onDrag = { change, _ ->
                        dragPositionMs = (change.position.x / size.width * durationMs).toLong()
                            .coerceIn(0L, durationMs)
                        onSeek(dragPositionMs)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        val centerY = h / 2f
        val hasData = waveforms.any { it != null }

        // 中线(无数据时的占位视觉)
        drawLine(
            color = unplayedColor.copy(alpha = 0.4f),
            start = Offset(0f, centerY),
            end = Offset(w, centerY),
            strokeWidth = 1f,
        )
        if (!hasData) return@Canvas

        val playedX = w * progress
        waveforms.forEachIndexed { trackIdx, data ->
            if (data == null) return@forEachIndexed
            val color = trackColors[trackIdx % trackColors.size]
            val n = data.pointCount
            if (n == 0) return@forEachIndexed

            // 柱宽自适应:每点一根线,留 1px 缝隙(点多于像素时自然密集成实心)
            val step = w / n
            val barWidth = (step * 0.7f).coerceAtLeast(1f)
            // 包络振幅 → 半高映射:最大 |value| 归一化,留 8% 上下边距
            val peak = maxOf(
                data.max.max().let { if (it.isNaN()) 0f else it },
                -data.min.min().let { if (it.isNaN()) 0f else it },
            ).coerceAtLeast(0.05f)
            val scaleY = (h / 2f) * 0.92f / peak

            for (i in 0 until n) {
                val x = i * step
                val lo = (data.min[i] * scaleY).coerceIn(-h / 2f, h / 2f)
                val hi = (data.max[i] * scaleY).coerceIn(-h / 2f, h / 2f)
                val top = centerY - hi
                val bottom = centerY - lo
                val c = if (x + step / 2 <= playedX) color else unplayedColor
                drawLine(
                    color = c,
                    start = Offset(x + step / 2, top),
                    end = Offset(x + step / 2, bottom),
                    strokeWidth = barWidth,
                )
            }
        }
    }
}