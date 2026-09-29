package pub.miku.neuralstem.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 均衡器**独立页**（导航 IA 重构 · 批次 1）。
 *
 * 为何从「播放器折叠卡片」提升为**顶级页**：
 * - EQ 是核心功能之一，不应藏在播放器的滚动卡片里；
 * - 独立页有更大纵向空间，段滑块行程更舒服；
 * - 导航栏一点即达（与「分离」平级）。
 *
 * 底层仍走 [PlayerViewModel] 的 EQ 接口（经 `PlaybackService` 下发），
 * 本页只是薄壳：标题 + 更多说明 + [EqPanel]。
 */
@Composable
fun EqScreen(
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
        Text("均衡器 (EQ)", style = MaterialTheme.typography.titleLarge)
        Text(
            "多段均衡，单轨 / 多轨均可用。拖动各段增益后即时生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
