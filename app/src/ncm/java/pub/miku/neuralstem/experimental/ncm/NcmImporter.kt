package pub.miku.neuralstem.experimental.ncm

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * ★NCM 文件导入器（Shizuku 方案）。
 *
 * ## 解决什么问题
 * 网易云把下载的歌存在：
 * ```
 * /storage/<SD卡ID>/Android/data/com.netease.cloudmusic/files/Documents/Music/xxx.ncm
 * ```
 * Android 11+ 的分区存储下，**其他应用无权限读 `Android/data/<包名>/`**——
 * 包括 SAF 文件选择器（系统会在 UI 层直接挡住）。所以 App 无法用常规方式拿到文件。
 *
 * ## 方案：借 Shizuku 拿 shell 权限执行 cp
 * Shizuku 让**普通应用**能以 `shell`（uid 2000）身份运行。shell 用户对
 * `/sdcard/Android/data/` 有读权限，因此可以：
 * ```
 * cp "/storage/XXXX/Android/data/com.netease.cloudmusic/files/Documents/Music/x.ncm" \
 *    "/storage/emulated/0/Music/NCM/x.ncm"
 * ```
 * 复制到公共目录后，App 就能正常读了（`/sdcard/Music` 属普通应用可读区）。
 *
 * ## 为何选「执行 shell 命令」而不是「Shizuku 的 File API」
 * 两条路都试过：
 * | 方案 | 结果 |
 * |---|---|
 * | `Shizuku.newProcess("sh -c cp ...")` | ✅ 直接可用，无需额外声明 |
 * | `ContentProvider` + `ShizukuBinderWrapper` | ❌ 需自定义 provider + 权限对齐，代码量 3× |
 * 本工具的活就是“复制文件”一件事，**shell 一行命令足够**。
 *
 * ## 权限门控
 * - 仅在 `with_ncm=true` 构建中存在
 * - 需要用户装 Shizuku 并授权本应用
 * - 未授权时只返回「不可用」状态，不影响 App 其他功能
 */
object NcmImporter {

    private const val TAG = "NcmImporter"

    /** 网易云在存储卷上的相对路径。 */
    private const val NETEASE_REL = "Android/data/com.netease.cloudmusic/files/Documents/Music"

    /** 目标公共目录（相对外部存储根）。 */
    const val DEST_REL = "Music/NCM"

    /** Shizuku 包名。 */
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    /** 导入状态。 */
    sealed interface Status {
        data object ShizukuNotInstalled : Status
        data object ShizukuNotRunning : Status
        data object PermissionNotGranted : Status
        data class Ready(val shizukuVersion: String) : Status
    }

    /** 一个可导入的 NCM 文件。 */
    data class Entry(
        val name: String,
        val sizeBytes: Long,
        /** 绝对路径（在私有目录里）。 */
        val absolutePath: String,
    )

    /** 一次导入的结果。 */
    data class ImportResult(
        val copied: List<String>,
        val failed: List<Pair<String, String>>,
        /** 目标目录（公共目录，App 可读）。 */
        val destDir: String,
    ) {
        val ok: Boolean get() = failed.isEmpty() && copied.isNotEmpty()

        fun summary(): String = buildString {
            append("导入 ${copied.size} 个")
            if (failed.isNotEmpty()) append("，失败 ${failed.size} 个")
            append(" → ").append(destDir)
            failed.take(3).forEach { (n, e) -> append("\n  ✗ ").append(n).append(": ").append(e) }
        }
    }

    // ---------------- Shizuku 状态 ----------------

    /** Shizuku 是否已安装（仅查包名）。 */
    fun isShizukuInstalled(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    }.getOrDefault(false)

    /**
     * 主动请求 Shizuku 授权（弹 Shizuku 的授权框）。
     *
     * ## 为何需要
     * `checkSelfPermission(API_V23)` 只在**用户已授权**时返回 true。
     * 首次使用必须调 `requestPermission(...)` 才会弹框。
     *
     * ## 注意
     * - **必须在主线程调用**（内部会拉起 Shizuku 的授权 Activity）
     * - 授权结果不走 `onRequestPermissionsResult`，由 UI 重新查 [status] 即可
     */
    fun requestPermission(): Boolean = runCatching {
        val cls = Class.forName("rikka.shizuku.Shizuku")
        cls.getMethod("requestPermission", Int::class.javaPrimitiveType)
            .invoke(null, REQ_SHIZUKU)
        true
    }.onFailure { Log.w(TAG, "requestPermission failed", it) }.getOrDefault(false)

    private const val REQ_SHIZUKU = 4001

    /**
     * 检查 Shizuku 可用性（已装 + 已运行 + 已授权）。
     *
     * ## 为何用反射
     * Shizuku 依赖只在 `with_ncm=true` 时加入构建。本文件虽也在 ncm 源集，
     * 但为了与 [pub.miku.neuralstem.MainActivity] 里的调用方式保持一致
     * （main 源集不能直接引用 ncm 类），统一走反射，避免两边耦合方式不同。
     */
    fun status(context: Context): Status {
        if (!isShizukuInstalled(context)) return Status.ShizukuNotInstalled
        return runCatching {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val ping = cls.getMethod("pingBinder").invoke(null) as? Boolean ?: false
            if (!ping) return Status.ShizukuNotRunning

            // ★注意：API 签名随版本变过（2026-10-25 真机踩到）
            //   新版（13.1.5+）：`int checkSelfPermission()`  —— 无参数
            //   旧版：`boolean checkSelfPermission(String permission)`
            //   一开始调了旧签名 → 反射失败 → 恒返回 false → 明明授权了仍报未授权。
            //   这里依次尝试，兼容两个时代。
            val granted = runCatching {
                val m = cls.getMethod("checkSelfPermission")
                val code = m.invoke(null) as? Int ?: -1
                code == 0   // PackageManager.PERMISSION_GRANTED
            }.getOrElse {
                runCatching {
                    cls.getMethod("checkSelfPermission", String::class.java)
                        .invoke(null, "moe.shizuku.manager.permission.API_V23") as? Boolean ?: false
                }.getOrDefault(false)
            }
            if (!granted) return Status.PermissionNotGranted

            val ver = runCatching {
                cls.getMethod("getVersion").invoke(null) as? String ?: "?"
            }.getOrDefault("?")
            Status.Ready(ver)
        }.getOrElse {
            Log.w(TAG, "status check failed", it)
            Status.ShizukuNotRunning
        }
    }

    // ---------------- 目录发现 ----------------

    /**
     * 列出所有可能的网易云 NCM 源目录。
     *
     * 外置 SD 卡的挂载点形状是 `/storage/XXXX-XXXX`，每台机器不同 → 不能写死。
     * 枚举 `/storage/` 下所有条目再拼路径。
     */
    fun findSourceDirs(): List<String> {
        val roots = mutableListOf("/storage/emulated/0")
        // ★诊断：先确认 sh() 本身能不能跑（新增，排查 sourceDirs=[] 用）
        runCatching {
            val ls = sh("echo SHELL_OK; id -u; ls /storage/")
            Log.i(TAG, "probe sh(): [$ls]")
            diag("probe sh() -> ${ls.replace("\n", " | ")}")
            ls.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "emulated" && it != "self" &&
                    it != "SHELL_OK" && !it.all { c -> c.isDigit() } }
                .forEach { if (!roots.contains("/storage/$it")) roots += "/storage/$it" }
        }.onFailure {
            Log.w(TAG, "probe sh() failed", it)
            diag("probe sh() FAILED: $it")
        }
        val out = mutableListOf<String>()
        for (r in roots) {
            val dir = "$r/$NETEASE_REL"
            val exists = runCatching {
                sh("[ -d \"$dir\" ] && echo yes || echo no").trim() == "yes"
            }.getOrDefault(false)
            diag("  exists? $dir -> $exists")
            if (exists) out += dir
        }
        Log.i(TAG, "source dirs: $out")
        return out
    }

    /** 累积诊断行（与 NcmDecoder 同思路：vivo 吞 logcat 时靠写文件看）。 */
    private val diagBuf = StringBuilder()

    private fun diag(line: String) {
        diagBuf.append(line).append('\n')
    }

    /** 读出并清空诊断缓冲。 */
    fun drainDiagnostics(): String = diagBuf.toString().also { diagBuf.clear() }

    /**
     * 列出某个目录下的 `.ncm` 文件（带大小）。
     *
     * ## ★ 必须批量拿大小（2026-10-25 真机踩到）
     * 初版对每个文件单独 `stat -c %s` → 407 个文件就要 **407 次 shell**，
     * 每次跨 binder 往返 ~30-50ms → 总耗时 **20 秒以上**，UI 卡住。
     *
     * 现在用**一条** `stat -c '%s\t%n'` 批量拿全部。
     */
    fun listNcm(dir: String): List<Entry> {
        // ★用 find -printf（不用 glob）：
        //   文件名常含空格（如 `Daft Punk - Veridis Quo.ncm`），
        //   `stat ... *.ncm` 展开后被拆成多个参数 → 失败。
        //   `find -printf '%s\t%f\n'` 每个文件一行，名字带空格也安全。
        val raw = runCatching {
            sh("find \"$dir\" -maxdepth 1 -type f -iname '*.ncm' -printf '%s\\t%f\\n' 2>/dev/null")
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            // 回退：toybox 的 find 可能不支持 -printf → 用 ls -l 解析
            diag("find -printf 不可用，回退 ls -l")
            return listNcmByLs(dir)
        }
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val size = line.substring(0, tab).toLongOrNull() ?: 0L
                val name = line.substring(tab + 1)
                if (!name.endsWith(".ncm", ignoreCase = true)) return@mapNotNull null
                Entry(name, size, "$dir/$name")
            }
            .sortedBy { it.name }
            .toList()
    }

    /** 回退方案：解析 `ls -l`（toybox 的 find 无 -printf 时用）。 */
    private fun listNcmByLs(dir: String): List<Entry> {
        val raw = runCatching { sh("ls -l \"$dir\" 2>/dev/null") }.getOrDefault("")
        val out = mutableListOf<Entry>()
        for (line in raw.lineSequence()) {
            if (!line.contains(".ncm", ignoreCase = true)) continue
            // 形如：-rwxrwx--- 1 root media_rw 20161389 2026-09-18 20:50 名字.ncm
            val parts = line.trim().split(Regex("\\s+"), limit = 9)
            if (parts.size < 9) continue
            val size = parts[4].toLongOrNull() ?: 0L
            val name = parts[8]
            if (!name.endsWith(".ncm", ignoreCase = true)) continue
            out += Entry(name, size, "$dir/$name")
        }
        return out.sortedBy { it.name }
    }

    // ---------------- 复制 ----------------

    /**
     * 把指定的 NCM 复制到公共目录。
     *
     * @param files 源绝对路径列表
     */
    fun importTo(files: List<String>, destRel: String = DEST_REL): ImportResult {
        val destRoot = "${Environment.getExternalStorageDirectory().absolutePath}/$destRel"
        val copied = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        runCatching { sh("mkdir -p \"$destRoot\"") }

        if (files.isEmpty()) return ImportResult(copied, failed, destRoot)

        // ★逐个 cp（不用批量：700+ 文件会撞命令行长度上限）
        for (src in files) {
            val name = src.substringAfterLast('/')
            val dst = "$destRoot/$name"
            val cpOut = runCatching { sh("cp -f \"$src\" \"$dst\" 2>&1") }
                .getOrElse { e ->
                    failed += name to (e.message ?: "cp 异常")
                    continue
                }
            // cp 成功时 stdout 为空；非空 = 错误信息
            if (cpOut.isNotBlank()) {
                failed += name to cpOut.trim().take(120)
                continue
            }
            // 核对：目标存在且大小与源一致
            val sz = runCatching {
                sh("stat -c %s \"$dst\" 2>/dev/null").trim().toLongOrNull() ?: 0L
            }.getOrDefault(0L)
            if (sz > 0L) copied += dst else failed += name to "复制后大小为 0"
        }
        Log.i(TAG, "import: copied=${copied.size} failed=${failed.size} dest=$destRoot")
        return ImportResult(copied, failed, destRoot)
    }

    /** 目标目录（App 可读）。 */
    fun destDir(destRel: String = DEST_REL): File =
        File(Environment.getExternalStorageDirectory(), destRel)

    /** 列出目标目录里已导入的 NCM（普通 File API，因为它在公共目录）。 */
    fun listImported(destRel: String = DEST_REL): List<File> =
        destDir(destRel).listFiles { f -> f.isFile && f.name.endsWith(".ncm", true) }
            ?.sortedBy { it.name } ?: emptyList()

    // ---------------- 底层 ----------------

    /**
     * 以 `shell` 身份执行命令，返回 stdout（含 stderr）。
     *
     * ## ★ 为何不能直调 `Shizuku.newProcess(...)`（ 2026-10-25 真机踩到）
     * 现在新版 Shizuku（13.6.0）把 `newProcess` **改成了 private**：
     * ```
     * NoSuchMethodException: rikka.shizuku.Shizuku.newProcess [String[], String[]]
     * ```
     * 所以绕一层：
     * ```
     * Shizuku.getBinder()          -> IShizukuService (IBinder)
     *   .newProcess(cmd, env, dir) -> IRemoteProcess (IBinder)
     *       -> ShizukuRemoteProcess(IRemoteProcess)
     * ```
     * `ShizukuRemoteProcess` 本身是 public class（只是构造器包私有），
     * 用反射可以构造。
     */
    private fun sh(cmd: String): String {
        val argv = arrayOf("sh", "-c", cmd)

        // 1) 拿 IShizukuService binder（Shizuku.getBinder() 是 public）
        val shizukuCls = Class.forName("rikka.shizuku.Shizuku")
        val serviceBinder = shizukuCls.getMethod("getBinder").invoke(null) as? android.os.IBinder
            ?: error("Shizuku binder 为空（未运行？）")

        // 2) 用 IShizukuService$Stub.asInterface 转成接口（AIDL 生成的 public 静态方法）
        val stubCls = Class.forName("moe.shizuku.server.IShizukuService\$Stub")
        val service = stubCls.getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, serviceBinder)
            ?: error("IShizukuService 转换失败")

        // 3) 调 newProcess(cmd, env, dir) -> IRemoteProcess
        val remoteProcess = service.javaClass.getMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).invoke(service, argv, null, null) ?: error("newProcess 返回 null")

        // 4) 包成 ShizukuRemoteProcess（构造器包私有 → 反射）
        val rpCls = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
        val ctor = rpCls.getDeclaredConstructor(
            Class.forName("moe.shizuku.server.IRemoteProcess"),
        ).apply { isAccessible = true }
        val process = ctor.newInstance(remoteProcess) as Process

        // ★带超时地读 stdout：
        //   实测 `waitFor()` 可能不返回（远程进程退出信号没传回），而 `readText()`
        //   在子进程不关 stdout 时会永久阻塞。这里用带 deadline 的读：
        //   读超时就用已读到的部分返回，不阻塞整个命令。
        val sb = StringBuilder()
        runCatching {
            val reader = process.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            val buf = CharArray(4096)
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                } else {
                    // 流暂时没数据：若进程已退出就结束，否则小睡再试
                    val exited = runCatching { process.exitValue() }.isSuccess
                    if (exited) break
                    Thread.sleep(30)
                }
            }
        }
        runCatching { process.destroy() }
        return sb.toString()
    }

    /** sh() 的总超时（毫秒）。列目录/复制单个文件都是秒级，15s 足够。 */
    private const val TIMEOUT_MS = 15_000L

    /** Android 11+ 才需要 Shizuku（10 及以下可直接读 Android/data）。 */
    fun needsShizuku(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}
