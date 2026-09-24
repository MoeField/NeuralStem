package pub.miku.neuralstem.spike

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

/**
 * 分离页(**可组合**，阶段 M2 任务 C 从 SpikeActivity 抽出)。
 *
 * 抽出理由:让「分离」成为 AppShell 的一个导航页(与播放器同一 Activity)，
 * 从而**移除跨 Activity 的 setResult 传参**(消除坑 32 那一类问题)。
 * SpikeActivity 仍保留，用于 `--ez auto` 自动化路径(复用本组合函数)。
 *
 * 与 guide.md 对应:F3.1 本地分离 / F3.2 进度 / Task 0.4 测量(耗时/PSS)。
 */
@Composable
fun SeparationScreen(
    modifier: Modifier = Modifier,
    vm: SpikeViewModel = viewModel(),
    /** Task 4.4:把分离结果交给播放器做多轨播放 */
    onPlayStems: (SpikeViewModel.State.Done) -> Unit = {},
    /** M4/S3:把 **4-stem** 结果（按 vocals/drums/bass/other 顺序）交给播放器 */
    onPlayStems4: (List<File>) -> Unit = {},
) {
    val state by vm.state.collectAsState()

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importAudio) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("分离 (sherpa-onnx · Spleeter 2-stem)", style = MaterialTheme.typography.titleLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                // Task 4.1:全格式(与播放侧一致)—— mp3/wav/flac/aac/m4a/ogg
                pickAudio.launch(
                    arrayOf(
                        "audio/*",
                        "application/ogg",
                        "application/x-flac",
                    ),
                )
            }) { Text("导入音频") }

            Button(onClick = vm::loadExternal) { Text("外部目录加载") }

            val enabled = state is SpikeViewModel.State.Ready
            Button(onClick = vm::startSeparation, enabled = enabled) { Text("开始分离") }
        }

        // V4(技术债收口):分离结果缓存管理入口。
        val cacheMsg = remember { mutableStateOf<String?>(null) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    val n = vm.clearCache()
                    cacheMsg.value = if (n < 0) "分离进行中,无法清理" else "已清理 $n 项缓存"
                },
            ) { Text("清理缓存") }
            val msg = cacheMsg.value
            if (msg != null) {
                Text(msg, style = MaterialTheme.typography.bodySmall)
            }
        }

        when (val s = state) {
            is SpikeViewModel.State.Idle -> Text("等待导入音频(mp3 / wav / flac / aac 均可)")
            is SpikeViewModel.State.Ready -> Text(
                "已导入: ${s.fileName}\n时长 ${s.durationMs / 1000}s @ ${s.sampleRate}Hz" +
                    "\n(分离前会自动归一化到 44.1kHz 立体声)"
            )
            is SpikeViewModel.State.Separating -> {
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("分离中 ${s.percent}% — 已耗时 ${s.elapsedMs / 1000}s")
                Text("峰值 PSS ${"%.1f".format(s.peakPssMb)} MB")
                Text("提示:分离在后台运行,可切走稍后回来", style = MaterialTheme.typography.bodySmall)
                Button(onClick = vm::cancelSeparation) { Text("取消分离") }
            }
            is SpikeViewModel.State.Done -> {
                Text(
                    if (s.fromCache) "✅ 命中缓存(未重新推理)" else "✅ 分离完成",
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("音频时长: ${s.audioDurationMs / 1000}s")
                if (!s.fromCache) {
                    Text("模型加载: ${s.modelLoadMs} ms")
                    Text("推理总耗时: ${s.inferTimeMs} ms")
                    Text(
                        "处理速度: " +
                            "${"%.2f".format(s.audioDurationMs / 1000.0 / (s.inferTimeMs / 1000.0))}x 实时",
                    )
                    Text("峰值 PSS: ${"%.1f".format(s.peakPssMb)} MB")
                }
                Text(
                    "输出:\n${s.vocalsFile.absolutePath}\n${s.accompanimentFile.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Task 4.4:一键把分离结果送进多轨播放
                Button(onClick = { onPlayStems(s) }) { Text("▶ 去多轨播放") }
            }
            is SpikeViewModel.State.Error -> {
                Text("❌ ${s.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = { vm.reset() }) { Text("重置") }
            }
        }

        // ---------------- M4/S3：4-stem（vocals/drums/bass/other）----------------
        Sp4Section(vm = vm, onPlayStems4 = onPlayStems4)
    }
}

/**
 * 4-stem 分离入口（阶段 M4/S3）。
 *
 * 走 `SeparationRepository.start4()`（含缓存/进度），与上方 2-stem 流程**互不干扰**。
 * 结果按 [Spleeter4Dsp.STEM_KINDS] 顺序交给播放器。
 */
@Composable
private fun Sp4Section(
    vm: SpikeViewModel,
    onPlayStems4: (List<File>) -> Unit,
) {
    val ctx = LocalContext.current
    val repo = remember { SeparationRepository.get(ctx.applicationContext as Application) }
    val repoState by repo.state.collectAsState()
    val ready = vm.state.collectAsState().value
    val canStart = ready is SpikeViewModel.State.Ready

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("4-stem (Spleeter 4-stems · 鼓/贝斯/人声/其他)", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { vm.sourceFileFor4()?.let { repo.start4(it) } },
            enabled = canStart,
        ) { Text("开始 4-stem 分离") }

        when (val s = repoState) {
            is SeparationRepository.State.Preparing -> Text("准备中…")
            is SeparationRepository.State.Running -> {
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("4-stem 分离中 ${s.percent}% — ${s.elapsedMs / 1000}s")
                Text("峰值 PSS ${String.format("%.1f", s.peakPssMb)} MB")
                Button(onClick = { repo.cancel() }) { Text("取消") }
            }
            is SeparationRepository.State.Done4 -> {
                Text(
                    if (s.fromCache) "✅ 4-stem 命中缓存" else "✅ 4-stem 完成",
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!s.fromCache) {
                    Text(
                        "处理速度: " +
                            "${String.format("%.2f", s.audioDurationMs / 1000.0 / (s.inferTimeMs / 1000.0))}x 实时",
                    )
                    Text("峰值 PSS: ${String.format("%.1f", s.peakPssMb)} MB")
                }
                Text(
                    "输出: " + s.stemFiles.joinToString { it.name },
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { onPlayStems4(s.stemFiles) }) { Text("▶ 去四轨播放") }
            }
            is SeparationRepository.State.Failed ->
                Text("❌ ${s.message}", color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}
