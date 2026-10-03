package pub.miku.neuralstem.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * **混音页**（★方案1：**EQ + 分轨混音** 合并）。
 *
 * 为何独立成页且把分轨混音搬离播放页：
 * - 播放页在多轨时被「分轨混音 + 分离开关」挤压 → **转盘/速度条被压到 ~54px**（真机实测）。
 * - EQ 与「分轨混音」都是**混音/后处理**，同类合并最自然；播放页只留一行紧凑「分离开关」。
 * - 导航：`歌单 · 播放器 · 分离 · 混音 · 设置`。
 */
@Composable
fun MixScreen(
    modifier: Modifier = Modifier,
    vm: PlayerViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("混音", style = MaterialTheme.typography.titleLarge)

        // ---- 分轨混音（多轨时；否则提示去分离）----
        Text("分轨混音", style = MaterialTheme.typography.titleMedium)
        if (state.isMultiTrack && state.tracks.isNotEmpty()) {
            TrackMixerPanel(
                tracks = state.tracks,
                onVolumeChange = vm::setTrackVolume,
                onToggleMute = vm::toggleMute,
                onToggleSolo = vm::toggleSolo,
                onExit = vm::exitMultiTrack,
            )
        } else {
            Text(
                "分轨混音：尚未分离。请先在「分离」页分离当前曲。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        // ---- 均衡器（EQ）----
        Text("均衡器 (EQ)", style = MaterialTheme.typography.titleMedium)
        EqPanel(
            available = state.eqAvailable,
            enabled = state.eqEnabled,
            freqsHz = state.eqFreqsHz,
            levelsMb = state.eqLevelsMb,
            onEnabledChange = vm::setEqEnabled,
            onBandChange = vm::setEqBandLevel,
            onReset = vm::resetEq,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.eqAvailable && !state.eqEnabled) {
            Text(
                "提示：直接拖动任意频段即可开启 EQ（默认微笑曲线：低/高音各 +3dB）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
