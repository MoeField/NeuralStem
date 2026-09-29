package pub.miku.neuralstem.spike

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pub.miku.neuralstem.export.StemExporter
import java.io.File

/** U6：高质量分轨的可选引擎。 */
enum class QualityEngine(val label: String) {
    AUTO("自动（本机最佳）"),
    SPLEETER4("Spleeter 4-stem"),
    DEMUCS("Demucs（音质最佳）"),
}

/**
 * 阶段 UI / U6+U7：**高质量分轨 + 导出**。
 *
 * - 目的：用**预处理 + 缓存**换取后续高质量实时播放。
 * - 默认 [QualityEngine.AUTO]（自动选本机可跑的最佳模型）。
 * - 不管实时性，给**取消**按钮（v1 不做断点续传）。
 * - 完成后可 **导出**（U7，WAV + 原曲，英文文件名，不带 EQ）。
 */
@Composable
fun QualitySeparationSection(
    vm: SpikeViewModel,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val repo = remember { SeparationRepository.get(app) }
    val state by repo.state.collectAsState()
    val vmState by vm.state.collectAsState()
    val canStart = vmState is SpikeViewModel.State.Ready
    var engine by rememberSaveable { mutableStateOf(QualityEngine.AUTO) }
    var exportMsg by remember { mutableStateOf<String?>(null) }

    val modelDir = File(app.filesDir, "spleeter4")
    val demucsModel = File(app.filesDir, "demucs/htdemucs_embedded.onnx")

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("高质量分轨（预处理，不管实时性）", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QualityEngine.entries.forEach { e ->
                FilterChip(
                    selected = engine == e,
                    onClick = { engine = e },
                    label = { Text(e.label) },
                )
            }
        }

        Text(
            when (engine) {
                QualityEngine.AUTO -> "自动：优先 Demucs（若已安装），否则 Spleeter 4-stem。"
                QualityEngine.SPLEETER4 -> "Spleeter 4-stem（约 2–7× 实时）"
                QualityEngine.DEMUCS -> "Demucs htdemucs（音质最佳，约 1× 实时）"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = {
                val src = vm.sourceFileFor4() ?: return@Button
                when (engine) {
                    QualityEngine.AUTO ->
                        if (demucsModel.exists()) repo.startDemucs(src) else repo.start4(src)
                    QualityEngine.SPLEETER4 -> repo.start4(src, modelDir)
                    QualityEngine.DEMUCS -> repo.startDemucs(src, demucsModel)
                }
            },
            enabled = canStart,
        ) { Text("开始高质量分轨") }

        when (val s = state) {
            is SeparationRepository.State.Preparing -> Text("准备中…")
            is SeparationRepository.State.Running -> {
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("分离中 ${s.percent}% — ${s.elapsedMs / 1000}s（可取消）")
                Button(onClick = { repo.cancel() }) { Text("取消") }
            }
            is SeparationRepository.State.Done4 -> {
                Text(
                    if (s.fromCache) "✅ 命中缓存" else "✅ 完成",
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!s.fromCache) {
                    Text(
                        "速度 ${String.format("%.2f", s.audioDurationMs / 1000.0 / (s.inferTimeMs / 1000.0))}x 实时，" +
                            "峰值 ${String.format("%.0f", s.peakPssMb)} MB",
                    )
                }
                val src = vm.sourceFileFor4()
                Button(onClick = {
                    exportMsg = StemExporter.export(
                        app,
                        src?.nameWithoutExtension ?: "song",
                        s.stemFiles,
                        original = src,
                    )
                }) { Text("导出到 Music/separate/") }
                exportMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            is SeparationRepository.State.Failed ->
                Text("❌ ${s.message}", color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}
