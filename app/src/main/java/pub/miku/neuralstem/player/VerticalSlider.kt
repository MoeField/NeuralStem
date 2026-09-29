package pub.miku.neuralstem.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import pub.miku.neuralstem.ui.theme.WavePlayed
import pub.miku.neuralstem.ui.theme.WaveRemaining
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 阶段 UI / R2：**自绘竖向 Slider**（真实竖向交互）。
 *
 * ## 为什么要自绘（而不用 `rotate`）
 * `Modifier.rotate` **只旋转绘制，不旋转触摸区**——实测：视觉上是长竖条，
 * 但可触摸区域仍只有未旋转的小横条，且拖拽轴仍是横向 ⇒ “拖不动/行程短”。
 * 故必须自绘：Canvas 画轨道/滑块 + `pointerInput` 按 **y 坐标**算值。
 *
 * ## 行为
 * - 顶端 = 最大值；底端 = 最小值。
 * - **拖动中只改本地视觉值**，**松手才 `onValueChange`**（避免 Sonic/AudioTrack 频繁重配 → “没声音”）。
 * - **吸附**：松手时若落在 [snapTargets] 的 [snapTolerance] 内 → 归位。
 * - **步进**（[step] > 0）：拖动值实时量化到 step 网格（**不绘制刻度点**）。
 * - **对数尺度**（[logScale] = true，要求 min>0）：位置分数 f ↔ 值 v 为
 *   `v = min·(max/min)^f`。因为速度区间形如 [1/X, X] 时 `√(min·max)=1.0`，
 *   ⇒ **1.0× 恒定居中**（线性尺度下中点会随 X 漂移）。
 */
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activeColor: Color = WavePlayed,
    trackColor: Color = WaveRemaining,
    label: (Float) -> String = { "%.2f".format(it) },
    snapTargets: List<Float> = emptyList(),
    snapTolerance: Float = 0f,
    /** ★R6：>0 时把拖动值量化到 step 网格（无刻度点绘制）。 */
    step: Float = 0f,
    /** ★对数尺度：min>0 时用对数映射（速度滑块用，保证 1.0× 居中）。 */
    logScale: Boolean = false,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    if (!dragging) local = value.coerceIn(valueRange.start, valueRange.endInclusive)

    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(enabled, valueRange, step, logScale) {
                    if (!enabled) return@pointerInput
                    val h = size.height.toFloat()
                    // ★对数尺度前提：两端 > 0；否则退化为线性
                    val useLog = logScale && valueRange.start > 0f && valueRange.endInclusive > 0f
                    val logMin = if (useLog) ln(valueRange.start) else 0f
                    val logMax = if (useLog) ln(valueRange.endInclusive) else 0f
                    fun quantize(v: Float): Float =
                        if (step > 0f) {
                            val steps = ((v - valueRange.start) / step).roundToInt()
                            (valueRange.start + steps * step)
                                .coerceIn(valueRange.start, valueRange.endInclusive)
                        } else {
                            v
                        }
                    /** 分数 f∈[0,1]（0=min, 1=max）→ 值。 */
                    fun valueOfFrac(f: Float): Float =
                        if (useLog) exp(logMin + f * (logMax - logMin)) else valueRange.start + f * span
                    fun valueAt(y: Float): Float {
                        val frac = (1f - y / h).coerceIn(0f, 1f)   // 顶端=max
                        return quantize(valueOfFrac(frac))
                    }
                    detectDragGestures(
                        onDragStart = { off -> dragging = true; local = valueAt(off.y) },
                        onDrag = { change, _ ->
                            change.consume()
                            local = valueAt(change.position.y)
                        },
                        onDragEnd = {
                            dragging = false
                            var v = local
                            snapTargets.firstOrNull { abs(v - it) <= snapTolerance }?.let { v = it }
                            local = v
                            onValueChange(v)
                        },
                        onDragCancel = { dragging = false },
                    )
                },
        ) {
            val cx = size.width / 2f
            val h = size.height
            val trackW = 10f
            // ★对数尺度下用对数比例定位滑柄（否则线性值会让 1.0× 偏离中点）
            val useLog = logScale && valueRange.start > 0f && valueRange.endInclusive > 0f
            val frac = if (useLog) {
                val lm = ln(valueRange.start)
                val lM = ln(valueRange.endInclusive)
                ((ln(local.coerceAtLeast(1e-6f)) - lm) / (lM - lm)).coerceIn(0f, 1f)
            } else {
                ((local - valueRange.start) / span).coerceIn(0f, 1f)
            }
            val thumbY = h - frac * h
            // 轨道底
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(cx - trackW / 2f, 0f),
                size = Size(trackW, h),
                cornerRadius = CornerRadius(trackW / 2f),
            )
            // 已选（从滑柄到底部）
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(cx - trackW / 2f, thumbY),
                size = Size(trackW, (h - thumbY).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(trackW / 2f),
            )
            // 滑柄
            drawCircle(color = Color(0xFF0B0F12), radius = 15f, center = Offset(cx, thumbY))
            drawCircle(
                color = activeColor,
                radius = 15f,
                center = Offset(cx, thumbY),
                style = Stroke(width = 4f),
            )
        }
        Text(label(local), style = MaterialTheme.typography.labelMedium)
    }
}
