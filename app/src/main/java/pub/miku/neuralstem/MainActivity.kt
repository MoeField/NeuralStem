package pub.miku.neuralstem

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pub.miku.neuralstem.devtools.TestCommandBus
import pub.miku.neuralstem.player.PlayerViewModel
import pub.miku.neuralstem.player.WaveformCache
import pub.miku.neuralstem.settings.ModelCatalog
import pub.miku.neuralstem.settings.ModelDownloader
import pub.miku.neuralstem.settings.SettingsRepository
import pub.miku.neuralstem.spike.DeviceBenchmark
import pub.miku.neuralstem.spike.StemOrderProbe
import pub.miku.neuralstem.ui.AppShell
import pub.miku.neuralstem.ui.theme.NeuralStemTheme
import java.io.File

/**
 * Task 1:主页面 = 基础播放器(guide F0.x)。
 * Spike 分离验证页保留入口(本页「Spike 分离」按钮)。
 *
 * VM 获取用 ViewModelProvider(AndroidViewModel 工厂),不用 by lazy ——
 * 后者在 Activity 重建时会创建**新** VM 实例,旧实例的 player 泄漏在后台继续播,
 * 新实例则丢失全部状态(Task 1 实测踩坑,见 critical_steps)。
 */
class MainActivity : ComponentActivity() {

    /** ★NCC 实证任务（长跑，避免重复点击时并发多份）。 */
    private var nccJob: Job? = null

    /** ★dlmodel 下载任务（长跑，避免并发多份；重发即取消旧的）。 */
    private var downloadJob: Job? = null

    private val playerVm: PlayerViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PlayerViewModel(application) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ★G-2a/G-1b：应用启动即尝试“精确命中内置表 → 免跑填 benchmark 缓存”。
        //   这样即使不打开设置页，已知 CPU 也不会被 G-1b 门控拦住（免跑即视为“已检测”）。
        runCatching { DeviceBenchmark(application).seedFromBuiltinIfExact() }
        // ★⑭c：启动清理损坏/半截的波形缓存（防“外圈波形只占一段”）。
        runCatching { WaveformCache.cleanCorrupt(application) }

        setContent {
            NeuralStemTheme {
                // 播放错误上浮到 Snackbar(F0.x 的兜底,避免静默失败)
                val errorMsg = remember { mutableStateOf<String?>(null) }
                val snackbar = remember { SnackbarHostState() }

                // V2(N4):player 由 PlaybackService 持有,经 MediaController 异步连接。
                // 开始时 player 可能还是 null —— 用 state 的变化驱动重挂,
                // 一旦 controller 就绪就把错误监听挂上(避免坑 34:注册时机扑空)。
                val errorListener = remember {
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            errorMsg.value = "播放失败: ${error.errorCodeName}"
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    playerVm.state.collect {
                        playerVm.player?.let { p ->
                            p.removeListener(errorListener)
                            p.addListener(errorListener)
                        }
                    }
                }

                // 自动化入口(Task 0c 精神延续):SAF 选择器无法用坐标点击驱动,
                // 改为 intent data 直传 Uri —— 验收走非 UI 通道:
                //   adb shell am start -n pub.miku.neuralstem/.MainActivity \
                //     -d "<content or file uri>" --ez auto_play true
                intent?.data?.let { uri ->
                    // ★互斥：若同时给了 mt_vocals/mt_accomp（多轨入口），则本单曲入口**不载入**，
                    //   否则两个入口会同时 loadAndPlay + loadMultiTrack → **两首同时出声**。
                    val hasMt = intent.getStringExtra("mt_vocals") != null &&
                        intent.getStringExtra("mt_accomp") != null
                    if (intent.getBooleanExtra("auto_play", false) && !hasMt) {
                        LaunchedEffect(uri) {
                            playerVm.loadAndPlay(uri, uri.lastPathSegment ?: "auto")
                            // M3.5/T4 自动化：可选“N ms 后再载入第二首”（验证切歌时 EQ 重挂）
                            //   --es second_file <path> --ei second_after 5000
                            val second = intent.getStringExtra("second_file")
                            val after = intent.getIntExtra("second_after", 0)
                            if (second != null && after > 0) {
                                delay(after.toLong())
                                Log.i("EqReload", "loading second file (切歌): $second")
                                playerVm.loadAndPlay(
                                    Uri.fromFile(File(second)),
                                    File(second).name,
                                )
                            }
                        }
                    }
                }

                // V1 自动化入口:直接载入两条 stem 文件进多轨模式,不依赖坐标点击。
                //   adb shell am start -n pub.miku.neuralstem/.MainActivity \
                //     --es mt_vocals <abs path> --es mt_accomp <abs path> --ez auto_play true
                val mtVocals = intent?.getStringExtra("mt_vocals")
                val mtAccomp = intent?.getStringExtra("mt_accomp")
                if (mtVocals != null && mtAccomp != null) {
                    LaunchedEffect(mtVocals, mtAccomp) {
                        playerVm.loadMultiTrack(
                            listOf(
                                Triple("vocals", "人声", File(mtVocals)),
                                Triple("accompaniment", "伴奏", File(mtAccomp)),
                            ),
                        )
                        playerVm.togglePlayPause()

                        // M4/S1：多轨位置采样（验证 seek 前后对齐）
                        //   --ez mt_pos_probe true  → seek(若有) 后每 500ms 打一次，共 ~12s
                        val posProbe = intent.getBooleanExtra("mt_pos_probe", false)
                        val seekTo = intent.getIntExtra("mt_seek_to", 0)

                        if (posProbe) {
                            // 先采几次“seek 前”
                            repeat(3) { delay(500); playerVm.debugPositions() }
                        }
                        if (seekTo > 0) {
                            delay(500)
                            Log.i("MtSync", "seekTo($seekTo)")
                            playerVm.seekTo(seekTo.toLong())
                        }
                        if (posProbe) {
                            // 再采“seek 后”一段时间
                            repeat(16) { delay(500); playerVm.debugPositions() }
                        }
                        intent.getFloatExtra("mt_speed", 0f).takeIf { it > 0f }?.let { sp ->
                            delay(4500)
                            Log.i("MtSync", "setSpeed($sp)")
                            playerVm.setSpeed(sp)
                        }
                        // M3.5/T2 自动化：可选“N ms 后退出多轨”（验证单↔多轨切换）
                        //   --ei mt_exit_after 6000
                        val exitAfter = intent.getIntExtra("mt_exit_after", 0)
                        if (exitAfter > 0) {
                            delay(exitAfter.toLong())
                            Log.i("MtSwitch", "triggering exitMultiTrack after ${exitAfter}ms")
                            playerVm.exitMultiTrack()
                        }
                        // ★I1 验证：**多轨下**再载入另一首 → 应先退出多轨（不应两首同时响）。
                        //   --es second_file <path> --ei second_after 3000
                        val secondFile = intent.getStringExtra("second_file")
                        val secondAfter = intent.getIntExtra("second_after", 0)
                        if (secondFile != null && secondAfter > 0) {
                            delay(secondAfter.toLong())
                            Log.i("I1Test", "load second file while multi-track: $secondFile")
                            playerVm.loadAndPlay(Uri.fromFile(File(secondFile)), File(secondFile).name)
                        }
                    }
                }
                // M2-D 自动化入口:载入音频后应用 EQ 测试曲线并打印诊断
                //   adb shell am start -n pub.miku.neuralstem/.MainActivity \
                //     -d "file:///...wav" --ez auto_play true --ez eq_test true
                if (intent?.getBooleanExtra("eq_test", false) == true) {
                    LaunchedEffect(Unit) {
                        // 等待音频就绪/会话建立后再挂 EQ
                        delay(4000)
                        val info = playerVm.applyEqTestCurve()
                        Log.i("EqDemo", "EQ_TEST $info")
                        Log.i("EqDemo", "EQ_SUMMARY ${playerVm.eqSummary()}")
                    }
                }

                LaunchedEffect(errorMsg.value) {
                    errorMsg.value?.let {
                        snackbar.showSnackbar(it)
                        errorMsg.value = null
                    }
                }

                // ★T0：测试命令总线消费循环（adb broadcast → 动作级驱动；见 devtools/）。
                //   执行点调用**与 UI 点击相同**的 VM 方法（测试路径 = 用户路径）。
                LaunchedEffect(Unit) {
                    TestCommandBus.pending.collect { cmd ->
                        if (cmd == null) return@collect
                        TestCommandBus.consume()
                        if (cmd.delayMs > 0) delay(cmd.delayMs)
                        dispatchTestCommand(cmd)
                    }
                }

                // M2-C:AppShell 取代原先「直接显示 PlayerScreen」;分离页现为其一导航项，
                // 分离结果直接回调 playerVm，不再走跨 Activity 的 setResult（消除坑 32）。
                AppShell(playerVm = playerVm, snackbarHostState = snackbar)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // VM 随 ViewModelStore 在旋转/重建间存活;仅真正退出(finish)时释放 player。
        // 重建场景若释放,重建后拿到的是已 release 的 player(实测踩坑)。
        if (isFinishing) playerVm.release()
    }

    /**
     * ★T0：执行一条测试命令。
     *
     * **原则**：每条命令调用与 UI 点击**完全相同**的 [PlayerViewModel] 方法，
     * 保证“测试路径 = 用户路径”，避免测试与真实行为分叉。
     */
    private fun dispatchTestCommand(cmd: TestCommandBus.Command) {
        val a0 = cmd.args.getOrNull(0)
        val a1 = cmd.args.getOrNull(1)
        when (cmd.name) {
            "help" -> Log.i("TestCmd", TestCommandBus.helpText)
            "load" -> if (a0 != null) {
                playerVm.loadAndPlay(Uri.parse(a0), a1 ?: a0.substringAfterLast('/'))
            }
            "play" -> playerVm.play()
            // 无独立 pause 方法（UI 只有 toggle）；pause 作为 toggle 别名。
            "pause", "toggle" -> playerVm.togglePlayPause()
            "seek" -> a0?.toLongOrNull()?.let { playerVm.seekTo(it) }
            "clear" -> playerVm.clearCurrent()
            "next" -> playerVm.playNext()
            "prev" -> playerVm.playPrev()
            // repeat: a0 ∈ {seq, all, one} —— 与 UI 选同一模式（“测试路径=用户路径”），T-4 验收用
            "repeat" -> when (a0?.lowercase()) {
                "seq", "sequential" -> playerVm.setRepeatMode(SettingsRepository.RepeatMode.SEQUENTIAL)
                "all", "loop_all" -> playerVm.setRepeatMode(SettingsRepository.RepeatMode.LOOP_ALL)
                "one", "loop_one" -> playerVm.setRepeatMode(SettingsRepository.RepeatMode.LOOP_ONE)
                else -> Log.w("TestCmd", "repeat: a0 需为 seq/all/one，收到 $a0")
            }
            // cyclerepeat: 同循环按钮点击（顺序→循环歌单→单曲循环→顺序）
            "cyclerepeat" -> playerVm.cycleRepeatMode()
            // toggleSep: a0 = true/false（分离开关；验证“关→再开恢复”）
            "togglesep" -> {
                val on = a0?.toBooleanStrictOrNull() ?: true
                val ok = playerVm.toggleSeparation(on)
                Log.i("TestCmd", "toggleSeparation($on) → $ok")
            }
            // queue: args = 交替的 uri,title（最多 2 首）；测试用
            "queue" -> {
                val pairs = cmd.args.chunked(2).mapNotNull { (u, t) ->
                    if (u.isBlank()) null else Uri.parse(u) to t
                }
                if (pairs.isNotEmpty()) playerVm.playQueue(pairs, 0)
            }
            "eq" -> playerVm.setEqEnabled(a0?.toBooleanStrictOrNull() ?: false)
            "inmt" -> if (a0 != null && a1 != null) {
                playerVm.loadMultiTrack(
                    listOf(
                        Triple("vocals", "人声", File(a0)),
                        Triple("accompaniment", "伴奏", File(a1)),
                    ),
                )
                playerVm.play()
            }
            "exitsmt" -> playerVm.exitMultiTrack()
            // ★ncmtest：NCM 解码验证（仅在 -Pwith_ncm=true 构建中存在）。
            //   a0 = .ncm 绝对路径。结果写 TAG=NcmVerify。
            //
            // ## 为何用反射而不是直接引用
            // `NcmVerifier` 位于 `src/ncm/` 源集，只有 `-Pwith_ncm=true` 时才被加进
            // `main` 源集。若直接 import，默认构建（with_ncm=false）会**编译失败**。
            // 反射让 main 源集零编译期依赖，两种构建都能编译。
            "ncmtest" -> {
                if (a0 == null) {
                    Log.w("TestCmd", "ncmtest 需要 a0=.ncm 文件路径")
                } else if (!BuildConfig.WITH_NCM) {
                    Log.w(
                        "TestCmd",
                        "ncmtest 不可用：本 APK 未启用 NCM 模块（需 -Pwith_ncm=true 构建）",
                    )
                } else {
                    val t0 = SystemClock.elapsedRealtime()
                    runCatching {
                        val cls = Class.forName("pub.miku.neuralstem.experimental.ncm.NcmVerifier")
                        val inst = cls.getField("INSTANCE").get(null)
                        // verifyAndReport(path, outDir)：结果写 filesDir，adb run-as 直接读。
                        // 为何不用 logcat：vivo/Android16 吞 app 日志（坑 52）。
                        val m = cls.getMethod(
                            "verifyAndReport",
                            String::class.java,
                            String::class.java,
                        )
                        val result = m.invoke(inst, a0, filesDir.absolutePath)
                        Log.i("TestCmd", "NCM_RESULT $result")
                        // ★可选：a1="bench" → 额外跑性能基准（实测解密耗时）
                        if (a1 == "bench") {
                            val bm = cls.getMethod("benchmark", String::class.java, Int::class.javaPrimitiveType)
                                .invoke(inst, a0, 3)
                            android.util.Log.i("TestCmd", "NCM_BENCH $bm")
                            File(filesDir, "ncmtest_result.txt")
                                .appendText("\n\n--- benchmark ---\n$bm\n")
                        }
                    }.onFailure { Log.e("TestCmd", "ncmtest 失败", it) }
                    Log.i("TestCmd", "ncmtest done in ${SystemClock.elapsedRealtime() - t0}ms")
                }
            }
            // ★dlmodel：批量下载模型（自动化用，避免 UI 逐个点击 19 次）。
            //   a0 = "all" 或逗号分隔的 ModelPack.id（如 "uvr-9482,uvr-kara"）。
            //   结果写 TAG=ModelDl，并同步进 TestLog（屏上可见）。
            "dlmodel" -> {
                val sel = a0?.trim().orEmpty()
                if (sel.isBlank()) {
                    Log.w("TestCmd", "dlmodel 需要 a0=all|<packId,...>")
                } else {
                    val packs = if (sel.equals("all", true)) {
                        ModelCatalog.ALL.map { it.pack }
                    } else {
                        sel.split(',').mapNotNull { id ->
                            ModelCatalog.ALL.firstOrNull { it.pack.id == id.trim() }?.pack
                                ?: run {
                                    Log.w("TestCmd", "dlmodel: 未知 pack id '$id'")
                                    null
                                }
                        }
                    }
                    if (packs.isEmpty()) {
                        Log.w("TestCmd", "dlmodel: 没有可下载的包")
                    } else {
                        Log.i("TestCmd", "dlmodel: ${packs.size} 个包 → ${packs.map { it.id }}")
                        downloadJob = lifecycleScope.launch {
                            val dl = ModelDownloader(filesDir).apply {
                                proxyProvider = { SettingsRepository(application).downloadProxy }
                            }
                            val pref = SettingsRepository(application).modelSourcePreference
                            val t0 = SystemClock.elapsedRealtime()
                            packs.forEachIndexed { i, p ->
                                Log.i("TestCmd", "dlmodel [${i + 1}/${packs.size}] start ${p.id}")
                                runCatching { dl.download(p, pref) }
                                    .onFailure { Log.e("TestCmd", "dlmodel ${p.id} failed", it) }
                                Log.i(
                                    "TestCmd",
                                    "dlmodel [${i + 1}/${packs.size}] done ${p.id} " +
                                        "installed=${ModelCatalog.installed(filesDir).size}",
                                )
                            }
                            val ids = ModelCatalog.installed(filesDir)
                            Log.i(
                                "TestCmd",
                                "DLMODEL_DONE total=${packs.size} installed=${ids.size}/${ModelCatalog.ALL.size} " +
                                    "elapsed=${SystemClock.elapsedRealtime() - t0}ms",
                            )
                        }
                    }
                }
            }
            "dlstatus" -> {
                val ids = ModelCatalog.installed(filesDir)
                Log.i(
                    "TestCmd",
                    "DLSTATUS installed=${ids.size}/${ModelCatalog.ALL.size} ids=$ids",
                )
            }
            // ★ncmimport：用 Shizuku 把网易云私有目录的 .ncm 复制到 /sdcard/Music/NCM。
            //   无参数 = 先报状态 + 列源目录 + 列文件（不复制）
            //   a0="copy" a1=<文件名或 all> = 真正复制
            //   结果同样写 filesDir/ncmtest_result.txt（vivo 吞 logcat）
            "ncmimport" -> {
                if (!BuildConfig.WITH_NCM) {
                    Log.w("TestCmd", "ncmimport 不可用：未启用 NCM 模块")
                } else if (a0 == "request") {
                    // 主动弹 Shizuku 授权框（需主线程）
                    runCatching {
                        val icls = Class.forName("pub.miku.neuralstem.experimental.ncm.NcmImporter")
                        val inst = icls.getField("INSTANCE").get(null)
                        val ok = icls.getMethod("requestPermission").invoke(inst) as Boolean
                        Log.i("TestCmd", "NCM requestPermission -> $ok")
                    }.onFailure { Log.e("TestCmd", "request failed", it) }
                } else {
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val sb = StringBuilder()
                        runCatching {
                            val icls = Class.forName(
                                "pub.miku.neuralstem.experimental.ncm.NcmImporter",
                            )
                            val inst = icls.getField("INSTANCE").get(null)

                            // ★分步记录：每一步都写文件，卡在哪一步一目了然
                            fun step(s: String) {
                                sb.append(s).append('\n')
                                runCatching {
                                    File(filesDir, "ncmtest_result.txt").writeText(sb.toString())
                                }
                            }

                            step("1. start")
                            val st = icls.getMethod("status", android.content.Context::class.java)
                                .invoke(inst, application)
                            step("2. status = $st")

                            // 单独测 sh()（这是最可能卡住的一步）
                            step("3. calling findSourceDirs...")
                            val dirs = icls.getMethod("findSourceDirs").invoke(inst) as List<*>
                            step("4. sourceDirs = $dirs")

                            // 列文件
                            val listM = icls.getMethod("listNcm", String::class.java)
                            val all = mutableListOf<String>()
                            for (d in dirs) {
                                val entries = listM.invoke(inst, d) as List<*>
                                step("5. $d -> ${entries.size} 个 .ncm")
                                entries.take(3).forEach { e ->
                                    step("     ${e?.toString()}")
                                }
                                all += entries.filterNotNull().mapNotNull { ent ->
                                    ent.javaClass.getMethod("getAbsolutePath")
                                        .invoke(ent) as? String
                                }
                            }

                            // 复制指定文件（a1=文件名）或全部（a1=all）
                            if (a0 == "copy" && all.isNotEmpty()) {
                                val toCopy = if (a1 == null || a1 == "all") all.take(3) else {
                                    all.filter { it.endsWith("/$a1") }
                                }
                                step("6. 复制 ${toCopy.size} 个...")
                                val ir = icls.getMethod(
                                    "importTo", List::class.java, String::class.java,
                                ).invoke(inst, toCopy, "Music/NCM")
                                step("7. result = $ir")
                            }

                            val d = icls.getMethod("drainDiagnostics").invoke(inst) as String
                            if (d.isNotBlank()) step("--- diag ---\n$d")
                        }.onFailure { sb.append("ERR: ").append(it).append('\n') }

                        Log.i("TestCmd", "NCMIMPORT\n$sb")
                        runCatching { File(filesDir, "ncmtest_result.txt").writeText(sb.toString()) }
                    }
                }
            }
            // ★stem 顺序实证（NCC）：a0 = 测试音频绝对路径
            //   结果写日志 TAG=StemOrderProbe，并用 adb logcat -s StemOrderProbe 读。
            "ncccheck" -> if (a0 != null) {
                nccJob = lifecycleScope.launch {
                    val probe = StemOrderProbe(application)
                    Log.i("TestCmd", "ncccheck 开始：$a0（13 个实验模型，每个约需 5-20s）")
                    val results = probe.run(File(a0)) { done, total, id ->
                        Log.i("TestCmd", "ncccheck 进度 $done/$total $id")
                    }
                    // 汇总表（便于一眼看完）
                    Log.i("TestCmd", "===== NCC 实证结果 =====")
                    results.forEach { r ->
                        val flag = when {
                            r.error != null -> "ERR(${r.error})"
                            !r.confident -> "UNCERTAIN(margin=%.3f)".format(r.margin)
                            else -> "OK"
                        }
                        Log.i(
                            "TestCmd",
                            "${r.modelId}: same=%.3f swap=%.3f → ${r.verdict} [$flag]"
                                .format(r.nccSame, r.nccSwap),
                        )
                    }
                    Log.i("TestCmd", "===== NCC 实证完毕 =====")
                }
            } else Log.w("TestCmd", "ncccheck 需要 a0=音频路径")
            else -> Log.w("TestCmd", "未知命令: ${cmd.name}（--es cmd help 查看清单）")
        }
    }
}