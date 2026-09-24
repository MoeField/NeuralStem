package pub.miku.neuralstem.spike

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pub.miku.neuralstem.player.PlayerViewModel
import pub.miku.neuralstem.ui.theme.NeuralStemTheme
import java.io.File

/**
 * Task 0b Spike 测试页(独立于主 UI)。
 *
 * 流程:选 WAV(44.1k 立体声)→ 开始分离 → 进度/耗时/PSS → 完成后给出输出路径,
 * 用 adb 或文件管理器拉出 vocals.wav / accompaniment.wav 人耳验收。
 *
 * 与 guide.md 的对应:
 * - F3.1 本地分离(验证中)
 * - F3.2 分离进度(粗粒度:块级进度)
 * - Task 0.4 测量:推理耗时 / 模型加载 / 峰值 PSS 全部展示
 */
class SpikeActivity : ComponentActivity() {

    companion object {
        /** Task 4.4:把分离产物路径回传给主页面 */
        const val EXTRA_VOCALS = "extra_vocals_path"
        const val EXTRA_ACCOMP = "extra_accomp_path"
        const val REQ_SEPARATE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Task 0c Phase A:自动化入口。带 "auto" extra 时由 ViewModel 内部单协程串联
        // "加载外部 WAV → 分离 → 写 stem WAV",绕过模拟器 DocumentsUI 不可用与
        // 坐标点击不可靠的问题。触发方式:
        //   adb shell am start -n pub.miku.neuralstem/.spike.SpikeActivity --ez auto true
        //
        // Task 0f 追加:可选 "engine" extra 选择分离引擎(UVR-MDX 对比实验):
        //   --es engine uvr   → UVR-MDX 单模型(--es uvr_model UVR-MDX-NET-Voc_FT.onnx 可选)
        //   --es engine spleeter → Spleeter(默认)
        if (intent?.getBooleanExtra("auto", false) == true) {
            Log.i(SpikeViewModel.TAG, "onCreate: AUTO mode, delegating to runAutoSpike()")
            val vm = ViewModelProvider(this)[SpikeViewModel::class.java]
            intent.getStringExtra("engine")?.let { vm.selectEngine(it) }
            intent.getStringExtra("uvr_model")?.let { vm.selectUvrModel(it) }
            // Task 0g B3:块长对比实验(--es chunk_seconds 10 → 10s 块)
            intent.getStringExtra("chunk_seconds")?.toIntOrNull()?.let {
                vm.selectChunkSeconds(it)
            }
            // Task 0g B2:线程对比实验(--es threads 2)
            intent.getStringExtra("threads")?.toIntOrNull()?.let { vm.selectThreads(it) }
            // Task 0g C1/C2:Spleeter 精度对照(--es spleeter_variant int8|fp32)
            intent.getStringExtra("spleeter_variant")?.let { vm.selectSpleeterVariant(it) }
            vm.runAutoSpike()
        }

        // 阶段 M2 任务 B:设备 benchmark 入口(供设置页与自动化共用)。
        //   adb shell am start -n pub.miku.neuralstem/.spike.SpikeActivity \
        //     --ez benchmark true [--es bench_mode long]
        if (intent?.getBooleanExtra("benchmark", false) == true) {
            val mode = if (intent.getStringExtra("bench_mode") == "long") {
                DeviceBenchmark.Mode.LONG
            } else {
                DeviceBenchmark.Mode.SHORT
            }
            lifecycleScope.launch {
                val r = DeviceBenchmark(application).run(mode)
                val summary = r.entries.joinToString { e -> e.key + "=" + e.value.rtf }
                Log.i("DevBench", "BENCH_DONE " + summary)
            }
        }

        // 阶段 M4/S2：Demucs 4-stem ONNX 真机探针（自写 JNI + ORT C API + 真实 STFT）。
        //   adb shell am start -n pub.miku.neuralstem/.spike.SpikeActivity --ez demucs_spike true
        if (intent?.getBooleanExtra("demucs_spike", false) == true) {
            lifecycleScope.launch {
                OrtProbe.probeWithRealAudio(filesDir)
            }
        }

        // 阶段 M4/S3：Spleeter 4-stem ONNX 真机探针（自写 JNI，4 个模型依次跑）。
        //   adb shell am start -n pub.miku.neuralstem/.spike.SpikeActivity --ez spleeter4_spike true
        if (intent?.getBooleanExtra("spleeter4_spike", false) == true) {
            lifecycleScope.launch {
                Spleeter4Probe.probe(filesDir)
            }
        }

        // 阶段 M4/S3：Spleeter 4-stem **端到端**（真实音频 → 4 轨落盘 + 重建不变量）。
        //   adb shell am start -n pub.miku.neuralstem/.spike.SpikeActivity \
        //     --ez spleeter4_full true --es sp4_input <abs wav path>
        if (intent?.getBooleanExtra("spleeter4_full", false) == true) {
            val inPath = intent.getStringExtra("sp4_input")
            lifecycleScope.launch {
                Spleeter4FullSpike.run(filesDir, inPath)
            }
        }

        // 阶段 M4/S3：走 **SeparationRepository 的 4-stem 路径**（含缓存/进度），
        // 验证生产路径（而非 Spike 直调）。
        //   adb shell am start -n pub.miku.neuralstem/.spike.SpikeActivity \
        //     --ez spleeter4_repo true --es sp4_input <abs wav path>
        if (intent?.getBooleanExtra("spleeter4_repo", false) == true) {
            val inPath = intent.getStringExtra("sp4_input")
            val repo = SeparationRepository.get(application)
            lifecycleScope.launch {
                repo.state.collect { s ->
                    Log.i("Sp4Repo", "state=${s.javaClass.simpleName} $s")
                    if (s is SeparationRepository.State.Done4) {
                        Log.i(
                            "Sp4Repo",
                            "SP4_REPO_DONE " + s.stemFiles.joinToString { "${it.name}(${it.length()}B)" } +
                                " inferMs=${s.inferTimeMs} peakPss=${s.peakPssMb} cache=${s.fromCache}",
                        )
                        return@collect
                    }
                    if (s is SeparationRepository.State.Failed) return@collect
                }
            }
            val src = if (inPath != null) File(inPath)
            else File(filesDir, "spleeter4/probe_input.wav")
            repo.start4(src)
        }

        // 阶段 M4/S3：4-stem 分离 → **四轨播放** 端到端（验证 M3 多轨引擎接 4 轨）。
        //   adb shell am start -n pub.miku.neuralstem/.spike.SpikeActivity \
        //     --ez spleeter4_play true --es sp4_input <abs wav path>
        if (intent?.getBooleanExtra("spleeter4_play", false) == true) {
            val inPath = intent.getStringExtra("sp4_input")
            val repo = SeparationRepository.get(application)
            val vm = ViewModelProvider(this)[PlayerViewModel::class.java]
            lifecycleScope.launch {
                repo.state.collect { s ->
                    Log.i("Sp4Play", "state=${s.javaClass.simpleName}")
                    if (s is SeparationRepository.State.Done4) {
                        val labels = listOf("人声", "鼓", "贝斯", "其他")
                        val items = s.stemFiles.mapIndexed { i, f ->
                            Triple(Spleeter4Dsp.STEM_KINDS[i], labels.getOrElse(i) { "轨$i" }, f)
                        }
                        Log.i("Sp4Play", "loading ${items.size} tracks: " + items.joinToString { it.second })
                        vm.loadMultiTrack(items)
                        vm.togglePlayPause()
                        repeat(6) { delay(700); vm.debugPositions() }
                        return@collect
                    }
                    if (s is SeparationRepository.State.Failed) return@collect
                }
            }
            val src = if (inPath != null) File(inPath)
            else File(filesDir, "spleeter4/probe_40s.wav")
            repo.start4(src)
        }

        // 阶段 M4/S2 落地：Demucs 4 源分离（真实音频 → 4 轨落盘）。
        //   adb shell am start -n pub.miku.neuralstem/.spike.SpikeActivity \
        //     --ez demucs_full true --es dm_input <abs wav path>
        if (intent?.getBooleanExtra("demucs_full", false) == true) {
            val inPath = intent.getStringExtra("dm_input")
            val repo = SeparationRepository.get(application)
            lifecycleScope.launch {
                repo.state.collect { s ->
                    Log.i("DmFull", "state=${s.javaClass.simpleName}")
                    if (s is SeparationRepository.State.Done4) {
                        Log.i(
                            "DmFull",
                            "DM_DONE " + s.stemFiles.joinToString { "${it.name}(${it.length()}B)" } +
                                " inferMs=${s.inferTimeMs} peakPss=${s.peakPssMb} cache=${s.fromCache}",
                        )
                        return@collect
                    }
                    if (s is SeparationRepository.State.Failed) return@collect
                }
            }
            val src = if (inPath != null) File(inPath)
            else File(filesDir, "demucs/probe_input.wav")
            val th = intent.getIntExtra("dm_threads", SeparationRepository.DEMUCS_THREADS)
            val nnapi = intent.getBooleanExtra("dm_nnapi", false)
            repo.startDemucs(src, threads = th, useNnapi = nnapi)
        }
        setContent {
            NeuralStemTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    SeparationScreen(
                        modifier = Modifier.padding(padding),
                        onPlayStems = { done ->
                            // 自动化路径:仍把两条 stem 路径回传给调用方
                            // (正常使用走 AppShell 的直连回调，不经此处)
                            setResult(
                                RESULT_OK,
                                Intent().apply {
                                    putExtra(EXTRA_VOCALS, done.vocalsFile.absolutePath)
                                    putExtra(EXTRA_ACCOMP, done.accompanimentFile.absolutePath)
                                },
                            )
                            finish()
                        },
                    )
                }
            }
        }
    }
}


