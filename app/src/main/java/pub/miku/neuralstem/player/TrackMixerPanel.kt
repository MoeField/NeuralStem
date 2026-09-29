package pub.miku.neuralstem.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 多轨混音面板(Task 4.4,guide F4.2/F4.3)。
 *
 * 每轨一行:轨名 + 音量滑块 + 静音 + 独奏。
 * 独奏与静音的互斥逻辑在 [PlayerViewModel.syncTrackState] / controller 里 ——
 * UI 只反映 [PlayerViewModel.TrackUiState]。
 */
@Composable
fun TrackMixerPanel(
    tracks: List<PlayerViewModel.TrackUiState>,
    onVolumeChange: (String, Float) -> Unit,
    onToggleMute: (String) -> Unit,
    onToggleSolo: (String) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("多轨混音", style = MaterialTheme.typography.titleSmall)
                FilledTonalButton(onClick = onExit) { Text("退出多轨") }
            }

            // ★ U2：2 轨特例 —— 单滑块（左右 = 两轨音量占比）
            if (tracks.size == 2) {
                TwoTrackBalance(tracks = tracks, onVolumeChange = onVolumeChange)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tracks.forEach { t ->
                        FilledTonalButton(onClick = { onToggleMute(t.id) }) {
                            Text((if (t.muted) "取消静音 " else "静音 ") + t.label)
                        }
                    }
                }
            } else {
                // 其他轨数（如 4 轨）：每轨一个滑块
                tracks.forEach { track ->
                    // ★ 本地值优先（与波形/进度条/竖向速度条同模式）：
                    //   拖动期间 Slider 只跟手指，**不被服务端快照回写覆盖**——
                    //   否则异步快照会与手指“打架”，表现为“拖不动（但音量确实变了）”。
                    var dragging by remember(track.id) { mutableStateOf(false) }
                    var localVol by remember(track.id) { mutableFloatStateOf(track.volume) }
                    if (!dragging) localVol = track.volume
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                track.label,
                                modifier = Modifier.width(72.dp),
                                fontWeight = if (track.solo) FontWeight.Bold else FontWeight.Normal,
                            )
                            Slider(
                                value = localVol,
                                onValueChange = {
                                    dragging = true
                                    localVol = it
                                    onVolumeChange(track.id, it)
                                },
                                onValueChangeFinished = { dragging = false },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${(localVol * 100).toInt()}%",
                                modifier = Modifier.width(44.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { onToggleMute(track.id) }) {
                                Text(if (track.muted) "已静音" else "静音")
                            }
                            FilledTonalButton(onClick = { onToggleSolo(track.id) }) {
                                Text(if (track.solo) "取消独奏" else "独奏")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * **U2：两轨单滑块**（用户指定：左右偏移表示对应轨道音量占比）。
 *
 * 滑块 `t ∈ [0,1]`：
 * - `t = 0.5` → 两轨均满音量（摆中）。
 * - `t < 0.5` → 轨0 满、轨1 衰减（左侧 = 轨0 占比大）。
 * - `t > 0.5` → 轨1 满、轨0 衰减（右侧 = 轨1 占比大）。
 */
@Composable
private fun TwoTrackBalance(
    tracks: List<PlayerViewModel.TrackUiState>,
    onVolumeChange: (String, Float) -> Unit,
) {
    val a = tracks[0]
    val b = tracks[1]

    val t = remember(a.volume, b.volume) {
        when {
            b.volume >= a.volume -> 0.5f * (b.volume / maxOf(a.volume, 1e-3f)).coerceIn(0f, 1f)
            else -> 1f - 0.5f * (a.volume / maxOf(b.volume, 1e-3f)).coerceIn(0f, 1f)
        }.coerceIn(0f, 1f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(a.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        // ★ 本地值优先：拖动期间只跟手指（快照回写会与手指打架 → “拖不动”）。
        var dragging by remember { mutableStateOf(false) }
        var localT by remember { mutableFloatStateOf(t) }
        if (!dragging) localT = t
        Slider(
            value = localT,
            onValueChange = { v ->
                dragging = true
                localT = v
                if (v <= 0.5f) {
                    onVolumeChange(a.id, 1f)
                    onVolumeChange(b.id, v / 0.5f)
                } else {
                    onVolumeChange(b.id, 1f)
                    onVolumeChange(a.id, (1f - v) / 0.5f)
                }
            },
            onValueChangeFinished = { dragging = false },
            modifier = Modifier.weight(1f),
        )
        Text(b.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
    }
    Text(
        "左偏 = ${a.label}；右偏 = ${b.label}（中点 = 等量）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
