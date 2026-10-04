package pub.miku.neuralstem

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import pub.miku.neuralstem.devtools.TestCommandBus
import pub.miku.neuralstem.player.PlayerViewModel
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
            else -> Log.w("TestCmd", "未知命令: ${cmd.name}（--es cmd help 查看清单）")
        }
    }
}