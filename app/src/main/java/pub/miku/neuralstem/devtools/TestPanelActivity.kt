package pub.miku.neuralstem.devtools

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import pub.miku.neuralstem.BuildConfig
import pub.miku.neuralstem.spike.DeviceBenchmark
import pub.miku.neuralstem.spike.SeparationRepository
import pub.miku.neuralstem.spike.SherpaSourceSeparation
import pub.miku.neuralstem.ui.theme.NeuralStemTheme
import java.io.File

/**
 * ★测试面板（debug 工具）：**按钮触发 + 屏上日志**。
 *
 * ## 为什么
 * 真机（vivo ROM）**吞 logcat** → 拿不到 infer 时间/状态。本页把日志**显示在屏幕上**，
 * 并用按钮直接驱动分离（不依赖 `SpikeActivity` 的后台/前台限制）。
 *
 * ```
 * adb shell am start -n pub.miku.neuralstem/.devtools.TestPanelActivity
 * ```
 *
 * 仅 debug 有意义（`BuildConfig.DEBUG`）；release 不会展示。
 */
class TestPanelActivity : ComponentActivity() {

    private val repo by lazy { SeparationRepository.get(application) }
    private val busy = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TestLog.log("Panel", "TestPanelActivity onCreate")

        setContent {
            NeuralStemTheme {
                val lines by TestLog.lines.collectAsState()
                val isBusy by busy.collectAsState()
                val repoState by repo.state.collectAsState()

                // 把 repo 状态变化也打进屏上日志（Running 限频：仅整 10% 打，避免刷屏）。
                var lastPct = -1
                LaunchedEffect(repoState) {
                    when (val s = repoState) {
                        is SeparationRepository.State.Running -> {
                            val pct = s.percent / 10 * 10
                            if (pct != lastPct) {
                                lastPct = pct
                                TestLog.append("Repo", "Running ${pct}% elapsed=${s.elapsedMs}ms pss=${s.peakPssMb}MB")
                            }
                        }
                        else -> TestLog.append("Repo", s.toString())
                    }
                }

                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Text("测试面板（debug）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "输入素材：cache/gn.wav（不存在则 gn.mp3）",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    // ---- 按钮区 ----
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            enabled = !isBusy,
                            onClick = { runBench(long = false) },
                        ) { Text("bench 短") }
                        OutlinedButton(
                            enabled = !isBusy,
                            onClick = { runBench(long = true) },
                        ) { Text("bench 长") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(enabled = !isBusy, onClick = { run2stem() }) { Text("2-stem") }
                        OutlinedButton(enabled = !isBusy, onClick = { run4stem() }) { Text("4-stem") }
                        OutlinedButton(enabled = !isBusy, onClick = { runDemucs() }) { Text("Demucs") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(enabled = !isBusy, onClick = { runUvr("UVR_MDXNET_9482.onnx") }) { Text("UVR9482") }
                        OutlinedButton(enabled = !isBusy, onClick = { runUvr("UVR-MDX-NET-Voc_FT.onnx") }) { Text("UVR Voc_FT") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(enabled = !isBusy, onClick = { repo.cancel() }) { Text("取消") }
                        OutlinedButton(onClick = { TestLog.clear() }) { Text("清日志") }
                    }

                    // ---- 屏上日志 ----
                    Text("日志（屏上）", style = MaterialTheme.typography.titleSmall)
                    val listState = rememberLazyListState()
                    // 新行出现时自动滚到底（否则最新 Done 行在可见区之外）
                    LaunchedEffect(lines.size) {
                        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFF101418))
                            .padding(6.dp),
                    ) {
                        items(lines) { ln ->
                            Text(
                                ln,
                                color = Color(0xFFB8E6C8),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun inputFile(): File {
        val candidates = listOf(File(cacheDir, "gn.wav"), File(cacheDir, "gn.mp3"), File(cacheDir, "kat.mp3"))
        val f = candidates.firstOrNull { it.exists() } ?: candidates[1]
        TestLog.log("Panel", "input = ${f.absolutePath} exists=${f.exists()} len=${f.length()}")
        return f
    }

    private fun runBench(long: Boolean) {
        busy.value = true
        val t0 = SystemClock.elapsedRealtime()
        TestLog.log("Panel", "=== benchmark ${if (long) "LONG" else "SHORT"} START ===")
        // 后台线程跑（benchmark 会阻塞数秒~数十秒）；不依赖协程 scope 避免误用
        Thread {
            try {
                val mode = if (long) DeviceBenchmark.Mode.LONG else DeviceBenchmark.Mode.SHORT
                val r = runBlocking { DeviceBenchmark(application).run(mode) }
                r.forEach { (id, b) -> TestLog.log("Bench", "$id rtf=%.2f ms=${b.ms}".format(b.rtf)) }
                TestLog.log("Panel", "=== bench DONE in ${SystemClock.elapsedRealtime() - t0}ms ===")
            } catch (t: Throwable) {
                TestLog.log("Panel", "bench FAILED: $t")
            } finally {
                busy.value = false
            }
        }.start()
    }

    private fun run2stem() {
        val f = inputFile()
        TestLog.log("Panel", "=== 2-stem START ($f) ===")
        repo.start(f, engine = SherpaSourceSeparation.MODE_SPLEETER)
    }

    private fun run4stem() {
        val f = inputFile()
        TestLog.log("Panel", "=== 4-stem START ($f) ===")
        repo.start4(f)
    }

    private fun runDemucs() {
        val f = inputFile()
        TestLog.log("Panel", "=== Demucs START ($f) ===")
        repo.startDemucs(f)
    }

    private fun runUvr(model: String) {
        val f = inputFile()
        TestLog.log("Panel", "=== UVR $model START ($f) ===")
        repo.start(f, engine = SherpaSourceSeparation.MODE_UVR, uvrModelName = model)
    }
}
