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

            tracks.forEach { track ->
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
                        // 音量滑块(F4.2):拖动实时生效
                        Slider(
                            value = track.volume,
                            onValueChange = { onVolumeChange(track.id, it) },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${(track.volume * 100).toInt()}%",
                            modifier = Modifier.width(44.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // F4.3 静音 / 独奏(两按钮均为切换态,不置灰)
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
