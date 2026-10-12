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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pub.miku.neuralstem.R

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
                Text(stringResource(R.string.track_mixer_title), style = MaterialTheme.typography.titleSmall)
                FilledTonalButton(onClick = onExit) { Text(stringResource(R.string.track_exit)) }
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
                    // ★⑬：静音 / 独奏 → 两个**窄图标**（横向并排），省横向空间。
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onToggleMute(track.id) }) {
                            Text(
                                if (track.muted) "🔇" else "🔈",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        TextButton(onClick = { onToggleSolo(track.id) }) {
                            Text(
                                if (track.solo) "🎧" else "🎶",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
