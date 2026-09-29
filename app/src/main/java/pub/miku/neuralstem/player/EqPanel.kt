package pub.miku.neuralstem.player

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 阶段 UI / U4：**均衡器面板**（成熟设计：多段滑块 + 开关 + 重置）。
 *
 * 底层在 Service 的 [EqController]（平台 `Equalizer`），UI 只是下发
 * 段增益（mB）与开关。段数/中心频率由 Service 快照回传（[PlayerViewModel.PlayerState.eqFreqsHz]）。
 *
 * @param available 硬件是否支持（false 时置灰）
 * @param enabled 当前开关
 * @param freqsHz 各段中心频率
 * @param levelsMb 各段增益（毫贝）
 */
@Composable
fun EqPanel(
    available: Boolean,
    enabled: Boolean,
    freqsHz: IntArray,
    levelsMb: ShortArray,
    onEnabledChange: (Boolean) -> Unit,
    onBandChange: (band: Int, levelMb: Short) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!available) {
            Text(
                "本机不支持均衡器（bandCount=0）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("启用 EQ", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        // 每段一根横向滑块（-15dB..+15dB → mB）。
        // ★3.2：
        //  - **关闭时也不置灰**（滑块保持可拖）；一旦拖动 → **自动开启 EQ**。
        //  - **不回弹**：拖动过的段记为“本地已改”，之后**不再被快照回写覆盖**（含松手后）。
        //    这样硬件回读的微调/旧快照都不会把手指放开的位拉回去。
        val localDirty = remember(freqsHz.size) { mutableStateMapOf<Int, Boolean>() }
        for (b in 0 until freqsHz.size) {
            val f = freqsHz[b]
            val snapshotDb = levelsMb.getOrElse(b) { 0 } / 100f
            var localDb by remember(b) { mutableFloatStateOf(snapshotDb) }
            // ★限频时间戳（毫秒；0 = 下次改动立即下发）
            var lastSendMs by remember(b) { mutableLongStateOf(0L) }
            // 仅在本段**未被本地改过**时，才跟随快照（初始化/外部重置）。
            if (localDirty[b] != true) {
                localDb = snapshotDb
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    formatFreq(f),
                    modifier = Modifier.width(56.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = localDb,
                    onValueChange = {
                        // 拖动即自动开启 EQ；同时标记本段本地已改。
                        if (!enabled) onEnabledChange(true)
                        localDirty[b] = true
                        localDb = it
                        // ★限频下发（~10Hz）：拖动中**也能听到实时变化**，又避免每帧重配 HAL 的爆音。
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastSendMs >= EQ_SEND_THROTTLE_MS) {
                            lastSendMs = now
                            onBandChange(b, (it * 100).toInt().toShort())
                        }
                    },
                    onValueChangeFinished = {
                        // ★松手：补发一次**精确终值**，并复位限频（下次拖动首帧立即生效）。
                        lastSendMs = 0L
                        onBandChange(b, (localDb * 100).toInt().toShort())
                    },
                    valueRange = -15f..15f,
                    enabled = true,   // ★始终可拖（不置灰）
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%+.1f".format(localDb) + " dB",
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        FilledTonalButton(
            onClick = {
                // 重置：清“本地已改”标记（允许重新跟随快照）+ 下发全零。
                localDirty.clear()
                onReset()
            },
            enabled = true,   // ★始终可用
        ) { Text("重置 EQ") }
    }
}

/** ★3.2：EQ 段写入限频窗口（ms）。拖动中每 ≥ 该间隔下发一次，兼顾实时听感与防爆音。 */
private const val EQ_SEND_THROTTLE_MS = 100L

/** 频率显示：<1000 → "250Hz"；否则 "1kHz"/"4.5kHz"。 */
private fun formatFreq(hz: Int): String =
    if (hz < 1000) "${hz}Hz"
    else {
        val k = hz / 1000.0
        if (k == k.toInt().toDouble()) "${k.toInt()}kHz" else "%.1fkHz".format(k)
    }
