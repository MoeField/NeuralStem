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
import kotlinx.coroutines.withContext
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

                            // 复制指定文件（a1=文件名）或全部（a1=all → 取前 3 个）
                            if (a0 == "copy" && all.isNotEmpty()) {
                                val toCopy = if (a1 == null || a1 == "all") all.take(3) else {
                                    all.filter { it.endsWith("/$a1") }
                                }
                                step("6. pull ${toCopy.size} 个（stdout 传字节）...")
                                val ir = icls.getMethod(
                                    "importByPull",
                                    android.content.Context::class.java,
                                    List::class.java,
                                    String::class.java,
                                ).invoke(inst, application, toCopy, "NCM")
                                step("7. result = $ir")
                                // 打印最近一次 pull 的错误
                                runCatching {
                                    val err = icls.getMethod("getLastError").invoke(inst) as? String
                                    if (err != null) step("   lastError = $err")
                                }
                                // 核对：App 能否读这些文件
                                val outDir = icls.getMethod(
                                    "destDir", android.content.Context::class.java, String::class.java,
                                ).invoke(inst, application, "NCM") as File
                                step("8. destDir=${outDir.absolutePath} exists=${outDir.exists()}")
                                outDir.listFiles()?.forEach { f ->
                                    step("   readable: ${f.name} canRead=${f.canRead()} len=${f.length()}")
                                }
                                // 核对：App 能否读这些文件
                                val destDir = icls.getMethod("destDir", String::class.java)
                                    .invoke(inst, "NCM") as File
                                destDir.listFiles()?.forEach { f ->
                                    step("   app-readable: ${f.name} canRead=${f.canRead()} len=${f.length()}")
                                }
                            }

                            val d = icls.getMethod("drainDiagnostics").invoke(inst) as String
                            if (d.isNotBlank()) step("--- diag ---\n$d")
                        }.onFailure { sb.append("ERR: ").append(it).append('\n') }

                        Log.i("TestCmd", "NCMIMPORT\n$sb")
                        runCatching { File(filesDir, "ncmtest_result.txt").writeText(sb.toString()) }
                    }
                }
            }
            // ★ncmplay：端到端验证 —— 解密 .ncm 后直接交给播放器。
            //   a0 = .ncm 路径。解密到内存 → 存 NcmPlaybackStore → ncm://<key> → ExoPlayer
            "ncmplay" -> {
                if (!BuildConfig.WITH_NCM) {
                    Log.w("TestCmd", "ncmplay 不可用：未启用 NCM 模块")
                } else if (a0 == null) {
                    Log.w("TestCmd", "ncmplay 需要 a0=.ncm 路径")
                } else {
                    // ★a0 = "auto" → 自动取 App 导入目录的第一个文件
                    //   （命令行传带空格的路径会被 am 拆参，auto 避开这个坑）
                    val realPath = if (a0 == "auto") {
                        runCatching {
                            val icls = Class.forName(
                                "pub.miku.neuralstem.experimental.ncm.NcmImporter",
                            )
                            val inst = icls.getField("INSTANCE").get(null)
                            val dir = icls.getMethod(
                                "destDir", android.content.Context::class.java, String::class.java,
                            ).invoke(inst, application, "NCM") as File
                            dir.listFiles { f -> f.name.endsWith(".ncm", true) }
                                ?.firstOrNull()?.absolutePath
                        }.getOrNull()
                    } else {
                        a0
                    }
                    if (realPath == null) {
                        Log.w("TestCmd", "ncmplay: 找不到可用文件")
                        runCatching {
                            File(filesDir, "ncmtest_result.txt")
                                .writeText("ERR: auto 模式找不到 .ncm（先跑 ncmimport copy）")
                        }
                    } else lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val sb = StringBuilder()
                        fun step(s: String) {
                            sb.append(s).append('\n')
                            runCatching {
                                File(filesDir, "ncmtest_result.txt").writeText(sb.toString())
                            }
                        }
                        runCatching {
                            step("1. start a0=$realPath")
                            val f = File(realPath)
                            step("2. exists=${f.exists()} size=${f.length()} canRead=${f.canRead()}")

                            val dcls = Class.forName(
                                "pub.miku.neuralstem.experimental.ncm.NcmDecoder",
                            )
                            val dst = dcls.getField("INSTANCE").get(null)
                            step("3. decoder class ok")
                            val t0 = SystemClock.elapsedRealtime()
                            val decoded = dcls.getMethod("decode", java.io.InputStream::class.java)
                                .invoke(dst, f.inputStream())
                            val ms = SystemClock.elapsedRealtime() - t0
                            val audio = decoded.javaClass.getMethod("getAudioBytes")
                                .invoke(decoded) as ByteArray
                            val fmt = decoded.javaClass.getMethod("getFormat")
                                .invoke(decoded) as String
                            step("4. decoded fmt=$fmt bytes=${audio.size} in ${ms}ms")

                            val scls = Class.forName(
                                "pub.miku.neuralstem.experimental.ncm.NcmPlaybackStore",
                            )
                            val sinst = scls.getField("INSTANCE").get(null)
                            val key = f.name
                            scls.getMethod("put", String::class.java, ByteArray::class.java)
                                .invoke(sinst, key, audio)
                            step("5. stored key=$key")

                            val uri = android.net.Uri.parse("ncm://$key")
                            step("6. loading $uri ...")
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                playerVm.loadAndPlay(uri, "NCM: $key")
                            }
                            step("7. loadAndPlay called")
                        }.onFailure { step("ERR: $it") }
                        Log.i("TestCmd", "NCMPLAY\n$sb")
                    }
                }
            }
            // ★ncmscan：批量统计 NCM 的容器/编码分布（a0 = 源目录，缺省用自动发现的第一个）
            //   用途：先搞清楚“有多少能播、多少是 AVS3”再决定要不要做解码器
            "ncmscan" -> {
                if (!BuildConfig.WITH_NCM) {
                    Log.w("TestCmd", "ncmscan 不可用：未启用 NCM 模块")
                } else {
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val sb = StringBuilder()
                        runCatching {
                            val vcls = Class.forName(
                                "pub.miku.neuralstem.experimental.ncm.NcmVerifier",
                            )
                            val vinst = vcls.getField("INSTANCE").get(null)
                            val icls = Class.forName(
                                "pub.miku.neuralstem.experimental.ncm.NcmImporter",
                            )
                            val iinst = icls.getField("INSTANCE").get(null)

                            // a0 = 源目录（缺省自动发现）；a1 = limit（缺省 500）
                            val limit = a1?.toIntOrNull() ?: 500
                            val dir = a0 ?: run {
                                val dirs = icls.getMethod("findSourceDirs").invoke(iinst) as List<*>
                                dirs.firstOrNull() as? String
                            }
                            sb.append("dir = ").append(dir).append(" limit=").append(limit).append('\n')
                            if (dir != null) {
                                val r = vcls.getMethod("scanCodecs", String::class.java, Int::class.javaPrimitiveType)
                                    .invoke(vinst, dir, limit) as String
                                sb.append(r)
                            } else {
                                sb.append("找不到源目录\n")
                            }
                        }.onFailure { sb.append("ERR: ").append(it).append('\n') }
                        Log.i("TestCmd", "NCMSCAN\n$sb")
                        runCatching { File(filesDir, "ncmtest_result.txt").writeText(sb.toString()) }
                    }
                }
            }
            // ★avs3test：端到端验证 AVS3 解码（ncm → m4a → 裸流 → PCM）
            //   a0 = .ncm 路径
            "avs3test" -> {
                if (!BuildConfig.WITH_NCM) {
                    Log.w("TestCmd", "avs3test 不可用：未启用 NCM 模块")
                } else if (a0 == null) {
                    Log.w("TestCmd", "avs3test 需要 a0=.ncm 路径")
                } else {
                    // ★a0 = "auto" → 自动取导入目录第一个 .ncm（避命令行空格）
                    val realPath = if (a0 == "auto") {
                        runCatching {
                            val icls = Class.forName(
                                "pub.miku.neuralstem.experimental.ncm.NcmImporter",
                            )
                            val inst = icls.getField("INSTANCE").get(null)
                            val dir = icls.getMethod(
                                "destDir", android.content.Context::class.java, String::class.java,
                            ).invoke(inst, application, "NCM") as File
                            dir.listFiles { f -> f.name.endsWith(".ncm", true) }
                                ?.firstOrNull()?.absolutePath
                        }.getOrNull()
                    } else a0
                    if (realPath == null) {
                        runCatching {
                            File(filesDir, "ncmtest_result.txt")
                                .writeText("ERR: auto 模式找不到 .ncm")
                        }
                    } else lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val sb = StringBuilder()
                        fun step(s: String) {
                            sb.append(s).append('\n')
                            runCatching {
                                File(filesDir, "ncmtest_result.txt").writeText(sb.toString())
                            }
                        }
                        runCatching {
                            val f = File(realPath)
                            step("1. file=${f.name} size=${f.length()} exists=${f.exists()}")

                            // --- 解密 NCM ---
                            val dcls = Class.forName(
                                "pub.miku.neuralstem.experimental.ncm.NcmDecoder",
                            )
                            val dst = dcls.getField("INSTANCE").get(null)
                            val decoded = dcls.getMethod("decode", java.io.InputStream::class.java)
                                .invoke(dst, f.inputStream())
                            val audio = decoded.javaClass.getMethod("getAudioBytes")
                                .invoke(decoded) as ByteArray
                            val fmt = decoded.javaClass.getMethod("getFormat")
                                .invoke(decoded) as String
                            step("2. ncm decoded: fmt=$fmt bytes=${audio.size}")

                            // --- 提取 MP4 音频轨 ---
                            val ecls = Class.forName(
                                "pub.miku.neuralstem.experimental.ncm.Mp4TrackExtractor",
                            )
                            val einst = ecls.getField("INSTANCE").get(null)
                            val res = ecls.getMethod("extractSamples", ByteArray::class.java)
                                .invoke(einst, audio)
                            if (res == null) {
                                step("3. ❌ MP4 提取失败（无音频轨？）")
                            } else {
                                val codec = res.javaClass.getMethod("getCodecTag").invoke(res) as String
                                @Suppress("UNCHECKED_CAST")
                                val samples = res.javaClass.getMethod("getSamples").invoke(res) as List<ByteArray>
                                val durMs = res.javaClass.getMethod("durationMs").invoke(res) as Long
                                step("3. MP4 提取: codec=$codec samples=${samples.size} dur=${durMs}ms")
                                // ★诊断：打印前几帧的头字节与大小（判断是不是 sequence header）
                                samples.take(5).forEachIndexed { i, s ->
                                    step(
                                        "   frame[$i] ${s.size}B head=${
                                            s.take(12).joinToString(" ") { "%02x".format(it) }
                                        }",
                                    )
                                }

                                // --- AVS3 解码 ---
                                val acls = Class.forName(
                                    "pub.miku.neuralstem.experimental.ncm.Avs3AudioDecoder",
                                )
                                val dec = acls.getConstructor().newInstance()
                                // ★无论走到哪个分支，都要保证 native 句柄被释放（否则泄露 + 下次跑干扰）
                                try {
                                    // Kotlin 的 `val available` 编译后是 `getAvailable()`
                                    val avail = acls.getMethod("getAvailable").invoke(dec) as Boolean
                                    step("4. decoder available=$avail")
                                    if (!avail) {
                                        step("   ❌ AVS3 库不可用（仅 arm64-v8a）")
                                    } else {
                                        val opened = acls.getMethod("open").invoke(dec) as Boolean
                                        step("5. open=$opened")
                                        if (!opened) {
                                            step("   ❌ open 失败（nativeCreate 返回 0）")
                                        } else {
                                            // ★按官方顺序初始化：parse_header(1) → parse_header(0)
                                            //   返回值：0 = 成功，负数 = 失败（内部已按 rc==1 才算成功判定）
                                            val ph = acls.getMethod(
                                                "initSequence", ByteArray::class.java,
                                            ).invoke(dec, samples[0]) as Int
                                            step("5b. initSequence rc=$ph (0=成功)")
                                            if (ph != 0) {
                                                step("   ⚠ init 失败，解码已跳过")
                                            } else {
                                                decodeAndReport(acls, dec, samples, durMs, a1, sb)
                                                Log.i("TestCmd", "AVS3TEST\n$sb")
                                            }
                                        }
                                    }
                                } finally {
                                    // ★资源释放放在 finally：available=false / open=false /
                                    //   init 失败 / 中途抛异常 四种路径都会执行到这里。
                                    runCatching { acls.getMethod("close").invoke(dec) }
                                }
                            }
                        }.onFailure { step("ERR: $it") }
                        Log.i("TestCmd", "AVS3TEST\n$sb")
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

    /**
     * ★AVS3 解码 + 统计 + 存证（`avs3test` 分支的实体部分）。
     *
     * 拆成独立方法而不是放在分支里，是因为那里已经是 3 层 lambda 嵌套，
     * 再塞逻辑会根本看不清括号归属。参数全用反射对象（Class/Method），
     * 保持 main 源集零编译期依赖。
     *
     * ## 两个不可变的约束
     * 1. **必须从 `samples[0]` 开始连续喂** —— AVS3 是有状态解码器，跳过前几帧
     *    会直接搞坏它内部参考帧/熵上下文，后面全是噪音。
     * 2. **只跑一轮** —— 之前 bug：先跑 `samples.take(60)` 再跑 `samples`，
     *    同一批数据被喂两遍，解码器状态被搞环 → 第 61 帧 native 崩溃。
     *
     * @param a1 帧数限制（`--es a1 200`），缺省 200
     */
    private fun decodeAndReport(
        acls: Class<*>,
        dec: Any,
        samples: List<ByteArray>,
        durMs: Long,
        a1: String?,
        sb: StringBuilder,
    ) {
        fun step(s: String) {
            sb.append(s).append('\n')
            runCatching { File(filesDir, "ncmtest_result.txt").writeText(sb.toString()) }
        }

        val decodeAll = acls.getMethod("decodeFrame", ByteArray::class.java)
        val limit = a1?.toIntOrNull() ?: 200
        val frames = samples.take(limit)
        val t0 = System.currentTimeMillis()
        var ok = 0
        var empty = 0
        var fail = 0
        var pcmBytes = 0L
        var firstChunk: ByteArray? = null
        var emptyHeadLogged = false
        val consumedList = ArrayList<Int>()
        val inLenList = ArrayList<Int>()

        /*
         * ★★ 关键修正（2026-10-26，实测坐实）：
         * `avs3_decode` 吃的是**纯帧载荷**，不包括帧头。
         *
         * 实测证据：MP4 每个样本 = 2415B（= 7B 帧头 + 2408B 载荷），
         * 而库返回 `lenConsumed = 2408` —— **恰好是去掉 7B 帧头后的长度**。
         * 之前我们把整份 2415B 喂进去，库只读前 2408B，
         * 于是每一帧都从「错位 7 字节」的位置开始解，自然全部是垃圾
         * （表现为满幅削波噪声，96.9% 样本贴 ±32767）。
         *
         * 另外根据官方 decoder.c，`Avs3InitDecoder` 里的 `bitsPerFrame`
         * **也不含** `NBITS_FRAME_HEADER_MC`，两边口径一致。
         */
        var headerBytes = -1
        for ((i, s) in frames.withIndex()) {
            // 首次调用时用库返回的 consumed 反推帧头长度，之后复用。
            val payload = if (headerBytes > 0 && s.size > headerBytes) {
                s.copyOfRange(headerBytes, s.size)
            } else {
                s
            }
            val pcm = decodeAll.invoke(dec, payload) as ByteArray?
            // ★把库实际消费的字节数也收下来（诊断帧边界用）。
            //   avs3_decode 不校验码流，bytesPerFrame 完全由帧头比特率决定，
            //   consumed 与 inLen 不相等就说明「帧头推出的帧长」与「实际样本切分」不自洽。
            inLenList.add(s.size)
            val consumed = runCatching {
                acls.getMethod("getLastConsumed").invoke(dec) as Int
            }.getOrDefault(-1)
            consumedList.add(consumed)
            // 首帧：拿 consumed 反推帧头长度（样本大小 - 库期望的载荷大小）
            if (headerBytes < 0 && consumed > 0 && s.size > consumed) {
                headerBytes = s.size - consumed
            }
            when {
                pcm == null -> fail++
                pcm.isEmpty() -> {
                    empty++
                    // ★前几帧空是正常的（含 sequence header），但若一开始就大量空
                    //   就要怀疑 access unit 切错了 —— 记一行头信息供人判断。
                    if (!emptyHeadLogged && i < 3) {
                        emptyHeadLogged = true
                        step(
                            "   frame[" + i + "] EMPTY（native 返回 0=无输出/DATA_NOT_ENOUGH）" +
                                " inLen=" + s.size + "B head=" +
                                s.take(9).joinToString(" ") { "%02x".format(it) },
                        )
                    }
                }
                else -> {
                    ok++
                    pcmBytes += pcm.size
                    if (firstChunk == null) firstChunk = pcm
                }
            }
        }
        val costMs = System.currentTimeMillis() - t0
        val audioSec = durMs / 1000.0
        val rtf = if (costMs > 0) audioSec / (costMs / 1000.0) else 0.0

        step("6. 解码: frames=${frames.size} ok=$ok empty=$empty fail=$fail")
        step("   pcmBytes=$pcmBytes")
        step("   cost=${costMs}ms audio=${durMs}ms RTF=${"%.2f".format(rtf)}×")

        // ★帧边界诊断：avs3_decode 不校验码流，bytesPerFrame 完全由帧头比特率决定。
        //   consumed == inLen == 2422 ⇒ 切分正确；否则帧头与样本切分不自洽，
        //   会导致 decoder 把垃圾当码流解 → 输出满幅削波噪声。
        if (consumedList.isNotEmpty()) {
            val firstC = consumedList.first()
            val firstIn = inLenList.first()
            val allSame = consumedList.distinct().size == 1
            step(
                "7. 帧边界: consumed=${firstC}B 样本=${firstIn}B 推断帧头=${headerBytes}B 全部相同=$allSame",
            )
            if (headerBytes > 0) {
                step("   ✓ 已按 ${headerBytes}B 帧头切离：喂给库的载荷 = ${firstIn - headerBytes}B = 库期望的 $firstC B")
            }
        }

        // ★推断声道数：pcmBytes / 帧数 / frameLength。
        //   包一层非空判断，否则全空帧时会除零（且 Kotlin 里 Int 除零是抛异常）。
        val outFrames = if (ok > 0) ok else 0
        if (outFrames > 0 && pcmBytes > 0) {
            val avgBytes = pcmBytes.toDouble() / outFrames
            step("   avgFramePCM=${avgBytes.toInt()}B")
            for (fl in intArrayOf(1024, 2048)) {
                step(
                    "   若 frameLength=$fl → 反推声道 = ${
                        "%.2f".format(avgBytes / (fl * 2))
                    }（12ch 应为 ${12 * fl * 2}B/帧）",
                )
            }
            // 首帧按各声道数假设的采样点数（b/2/声道数）
            firstChunk?.let { f ->
                step(
                    "   首帧 ${f.size}B → samples: 2ch=${
                        f.size / 4
                    } 6ch=${f.size / 12} 8ch=${f.size / 16} 10ch=${f.size / 20} 12ch=${f.size / 24}",
                )
                val nz = f.count { it != 0.toByte() }
                step(
                    "   首帧 head=${f.take(16).joinToString(" ") { "%02x".format(it) }}",
                )
                step(
                    "   首帧 nonZero=$nz/${f.size} = ${
                        "%.1f".format(nz * 100.0 / f.size)
                    }%${if (nz == 0) "（全零：解码器未真正出音）" else ""}",
                )
            }
        } else if (pcmBytes > 0) {
            // ok==0 但 pcmBytes>0 不可能，这里只是防御；写上便于发现异常。
            step("   ⚠ ok=$ok 但 pcmBytes=$pcmBytes，计数异常")
        }

        // ★写一个可播的 probe wav：固定按 48kHz 假设 + 声道数按每帧字节数反推。
        //   目的是能 `adb pull` 出来直接在 Mac 上听，而不是只看数字。
        if (pcmBytes > 0) {
            val chBytes = (pcmBytes / frames.size).toInt()
            val framesInPcm = (pcmBytes / (2 * maxOf(1, guessChannels(chBytes)))).toInt()
            step("   probe: 按 48kHz 输出前 $framesInPcm 帧（约 ${framesInPcm / 48000.0}s）")
            runCatching {
                writeProbeWav(pcmBytes, frames, decodeAll, dec, headerBytes)
                step("   probe wav → filesDir/avs3_probe.wav")
            }.onFailure { step("   probe wav 失败: $it") }
        }
    }

    /**
     * 把 PCM 写成 `files/avs3_probe.wav`，方便直接 `adb pull` 到 Mac 上播。
     *
     * ★声道数是**猜**的：官方库没导出声道信息（`Avs3DecoderLibGetConfig` 不在 ABI 里），
     *   只能从「每帧字节数 / 2 / frameLength」反推。这里取 1024 作为 frameLength 假设，
     *   因为 AVS3-P3 主流是 1024。猜错的话听感会是变速/变调，但能立刻听出「有没有声音」。
     */
    private fun writeProbeWav(
        pcmBytes: Long,
        frames: List<ByteArray>,
        decodeAll: java.lang.reflect.Method,
        dec: Any,
        headerBytes: Int,
    ) {
        /*
         * ★采样率必须是 44100（2026-10-26 实测）：
         * `avs3SamplingRateTable` = {192000, 96000, 48000, 44100, 32000, ...}，
         * 本文件的 samplingRateIdx=3 → **44100**。
         * 之前硬编码 48000 → 导出时长被拉长 8.8%（音高偏低）。
         */
        val sampleRate = 44100
        val chGuess = guessChannels((pcmBytes / frames.size).toInt())
        // ★整首导出（之前只取 2 秒，不够判断是不是这首歌）。
        //   一首 2分10秒 的 12ch/44100 约 **138MB**。
        //   ⚠ 不能用 ByteArrayOutputStream 累加：它扩容要 copy + 翻倍，
        //   130MB 峰值翻倍很容易 OOM。改为「先按已知帧数算好精确大小，
        //   再一次性分配 + 用 System.arraycopy 填」。
        val perFrame = (pcmBytes / frames.size).toInt()   // 每帧 PCM 字节
        if (perFrame <= 0) return
        val total = perFrame * frames.size
        if (total <= 0) return
        val buf = ByteArray(total)
        var off = 0
        for (s in frames) {
            // ★同样要跳帧头（见 decodeAndReport 里的说明）
            val payload = if (headerBytes > 0 && s.size > headerBytes) {
                s.copyOfRange(headerBytes, s.size)
            } else {
                s
            }
            val chunk = decodeAll.invoke(dec, payload) as ByteArray?
            if (chunk == null || chunk.isEmpty()) continue
            val n = minOf(chunk.size, total - off)
            if (n <= 0) break
            System.arraycopy(chunk, 0, buf, off, n)
            off += n
        }
        if (off == 0) return
        File(filesDir, "avs3_probe.wav").outputStream().use { out ->
            val dataLen = off
            val hdr = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            hdr.put("RIFF".toByteArray(Charsets.US_ASCII))
            hdr.putInt(36 + dataLen)
            hdr.put("WAVE".toByteArray(Charsets.US_ASCII))
            hdr.put("fmt ".toByteArray(Charsets.US_ASCII))
            hdr.putInt(16)                                   // PCM 格式块大小
            hdr.putShort(1)                                  // PCM
            hdr.putShort(chGuess.toShort())                  // 声道数
            hdr.putInt(sampleRate)
            hdr.putInt(sampleRate * chGuess * 2)             // 字节率
            hdr.putShort((chGuess * 2).toShort())            // 块对齐
            hdr.putShort(16)                                 // 位深
            hdr.put("data".toByteArray(Charsets.US_ASCII))
            hdr.putInt(dataLen)
            out.write(hdr.array())
            out.write(buf, 0, off)
        }
        Log.i("TestCmd", "avs3_probe.wav: $off bytes, ${chGuess}ch@$sampleRate")
    }

    /**
     * 从「每帧 PCM 字节数」反推声道数（在最接近的候选里选）。
     *
     * 每帧字节 = 声道数 × frameLength × 2。frameLength 取 1024（AVS3-P3 主流），
     * 即 frameLenHint = 2048 B/声道/帧。候选声道用规范里的 2/6/8/10/12/24 几档，
     * 取相对误差最小的那个 —— 只能猜，不能保证（库没暴露 config）。
     */
    private fun guessChannels(bytesPerFrame: Int): Int {
        if (bytesPerFrame <= 0) return 2
        val frameLenHint = 1024 * 2
        val chans = intArrayOf(2, 6, 8, 10, 12, 24)
        var best = 2
        var bestErr = Double.MAX_VALUE
        for (ch in chans) {
            val expected = ch * frameLenHint
            val err = kotlin.math.abs(expected - bytesPerFrame).toDouble() / expected
            if (err < bestErr) {
                bestErr = err
                best = ch
            }
        }
        return best
    }
}