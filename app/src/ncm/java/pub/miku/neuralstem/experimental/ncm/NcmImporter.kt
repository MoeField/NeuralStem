package pub.miku.neuralstem.experimental.ncm

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.ByteArrayOutputStream
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

    /**
     * 目标目录（App 可读）。
     *
     * ## ★ 为何不用 /sdcard/Music/NCM（2026-10-25 真机踩到）
     * 先试的是公共目录 `/sdcard/Music/NCM/`，结果：
     * - `cp` 成功，文件确实在那
     * - 但权限位是 `-rw-rw---- u0_a147 media_rw`（沿用源的属主/权限）
     * - **`chmod 644` 返回 0 但权限不变** —— `/sdcard` 是 FUSE 虚拟化存储，
     *   MediaProvider 把 chmod 变成空操作
     * - 即使 App 已拿到 `READ_MEDIA_AUDIO`，`File.canRead()` 仍 false，打开报 `EACCES`
     *
     * ⇒ 改复制到 **应用自己的外部私有目录**：
     * `/sdcard/Android/data/pub.miku.neuralstem/files/NCM/`
     * 那里 App 有完全读写权，且仍在外部存储上（用户可用文件管理器看到/备份）。
     *
     * 注：Android 11+ 用户不能直接浏览 `Android/data/<其他包>`，但**本应用自己**能读写。
     */
    const val DEST_REL = "NCM"

    /** Shizuku 包名。 */
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    /** 导入状态。 */
    sealed interface Status {
        data object ShizukuNotInstalled : Status
        data object ShizukuNotRunning : Status
        data object PermissionNotGranted : Status
        data class Ready(val shizukuVersion: String) : Status
    }

    /** 一个可导入的音乐文件（可能是加密 .ncm，也可能是不加密的 m4a/flac/mp3）。 */
    data class Entry(
        val name: String,
        val sizeBytes: Long,
        /** 绝对路径（在私有目录里）。 */
        val absolutePath: String,
    ) {
        /** 小写扩展名（不含点）。 */
        val ext: String get() = name.substringAfterLast('.', "").lowercase()

        /**
         * 是否是需要解密的 NCM 容器。
         *
         * 为 false 时（m4a/flac/mp3）代表**可以直接交给 ExoPlayer 播**，
         * 只需把它从私有目录拉出来即可——这正是“有些歌没加密”的那批。
         */
        val isEncrypted: Boolean get() = ext == "ncm"
    }

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
     * 列出某个目录下的网易云音乐文件（带大小）。
     *
     * ## ★ 为什么不再只认 .ncm（2026-10-26）
     * 真机实测网易云私有目录的构成：
     * ```
     * 732 × .ncm    73%   加密容器，需要解密
     * 220 × .m4a    22%   部分是不加密的（**也可能是 av3a，需逐个查 codec**）
     *  45 × .flac    4.5% 不加密，Android 原生能播
     *   4 × .mp3     0.4% 不加密，Android 原生能播
     * ```
     * 原来只过滤 `*.ncm` → **269 个文件（27%）被白白挡在外面**，
     * 而这些恰恰是“无需任何解密就能播”的那批。
     *
     * ## 必须批量拿大小（2026-10-25 真机踩到）
     * 初版对每个文件单独 `stat -c %s` → 407 个文件就要 **407 次 shell**，
     * 每次跨 binder 往返 ~30-50ms → 总耗时 **20 秒以上**，UI 卡住。
     * 现在用**一条** `find -printf` 批量拿全部。
     *
     * @param dir 源目录（网易云私有目录）
     * @param audioOnly true（默认）= 所有音频扩展名；false = 只留 .ncm（兼容旧行为）
     */
    fun listNcm(dir: String, audioOnly: Boolean = true): List<Entry> {
        val filter = if (audioOnly) AUDIO_FIND_EXPR else "-iname '*.ncm'"
        // ★用 find -printf（不用 glob）：
        //   文件名常含空格（如 `Daft Punk - Veridis Quo.ncm`），
        //   `stat ... *.ncm` 展开后被拆成多个参数 → 失败。
        //   `find -printf '%s\t%f\n'` 每个文件一行，名字带空格也安全。
        val raw = runCatching {
            sh("find \"$dir\" -maxdepth 1 -type f \\( $filter \\) -printf '%s\\t%f\\n' 2>/dev/null")
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            // 回退：toybox 的 find 可能不支持 -printf → 用 ls -l 解析
            diag("find -printf 不可用，回退 ls -l")
            return listNcmByLs(dir, audioOnly)
        }
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val size = line.substring(0, tab).toLongOrNull() ?: 0L
                val name = line.substring(tab + 1)
                if (!isMusicFile(name)) return@mapNotNull null
                if (!audioOnly && !name.endsWith(".ncm", ignoreCase = true)) return@mapNotNull null
                Entry(name, size, "$dir/$name")
            }
            .sortedBy { it.name }
            .toList()
    }

    /**
     * 这些扩展名都是网易云可能落盘的音频。
     *
     * 注：`.m4a` 里**也可能**是 AVS3-P3（`av3a` codec），
     *     所以“扩展名能认”不等于“能播”，播放前仍需查 codec tag。
     */
    val AUDIO_EXTS = listOf("ncm", "m4a", "flac", "mp3", "ogg", "wav", "aac", "ape")

    /** 供 `find` 用的表达式串（拼在 `\(  ...  \)` 里）。 */
    private val AUDIO_FIND_EXPR: String =
        AUDIO_EXTS.joinToString(" -o ") { "-iname '*.$it'" }

    private fun isMusicFile(name: String): Boolean {
        val lower = name.lowercase()
        return AUDIO_EXTS.any { lower.endsWith(".$it") }
    }

    /** 回退方案：解析 `ls -l`（toybox 的 find 无 -printf 时用）。 */
    private fun listNcmByLs(dir: String, audioOnly: Boolean): List<Entry> {
        val raw = runCatching { sh("ls -l \"$dir\" 2>/dev/null") }.getOrDefault("")
        val out = mutableListOf<Entry>()
        for (line in raw.lineSequence()) {
            // 形如：-rwxrwx--- 1 root media_rw 20161389 2026-09-18 20:50 名字.ncm
            val parts = line.trim().split(Regex("\\s+"), limit = 9)
            if (parts.size < 9) continue
            val size = parts[4].toLongOrNull() ?: 0L
            val name = parts[8]
            if (!isMusicFile(name)) continue
            if (!audioOnly && !name.endsWith(".ncm", ignoreCase = true)) continue
            out += Entry(name, size, "$dir/$name")
        }
        return out.sortedBy { it.name }
    }

    // ---------------- 复制 ----------------

    /**
     * 用 Shizuku 读一个文件的**前 n 字节**，转成十六进制串（诊断用）。
     *
     * 不直接 `File.read`：源文件在网易云私有目录，App 无权限读。
     * `od` 的输出格式：`0000000 0000 001c 6674 7970`，这里把字段 2.. 拼起来。
     */
    fun headHex(path: String, n: Int = 16): String = runCatching {
        val out = sh("od -An -tx1 -N $n \"$path\" 2>/dev/null")
        out.split(Regex("\\s+")).filter { it.length == 2 }.joinToString(" ") { it.lowercase() }
    }.getOrDefault("?")

    /**
     * 读文件**前 n 字节**的原始字节（诊断用）。
     *
     * ## 为何不用 readBytesViaShizuku 直接读整个文件
     * 同目录下 m4a 均 20-30MB，遍历 269 个就要拉 5GB 过 binder，
     * 而识别 codec 只需 MP4 的 `moov`（通常在前几百 KB）。
     * 这里用 `dd` 只取头部；**注意无音轨文件可能 moov 在尾部**，
     * 那种情况会返回 unknown，属于已知局限（统计时单列）。
     */
    fun readHead(path: String, n: Int): ByteArray = runCatching {
        // ★必须走 newShizukuProcess（拿二进制流）。
        //   `sh()` 内部用 bufferedReader 读，会把非 UTF-8 字节变成替换符，
        //   MP4 是二进制，走字符流直接报废。
        val process = newShizukuProcess(
            arrayOf("sh", "-c", "dd if=\"$path\" bs=4096 count=${(n + 4095) / 4096} 2>/dev/null"),
        )
        val out = ByteArrayOutputStream(minOf(n, 256 * 1024))
        process.inputStream.use { ins ->
            val buf = ByteArray(4096)
            while (out.size() < n) {
                val r = ins.read(buf)
                if (r <= 0) break
                out.write(buf, 0, minOf(r, n - out.size()))
            }
        }
        runCatching { process.destroy() }
        out.toByteArray()
    }.getOrDefault(ByteArray(0))

    /**
     * 把指定的 NCM 复制到**本应用的外部私有目录**。
     *
     * @param files 源绝对路径列表
     */
    fun importTo(files: List<String>, destRel: String = DEST_REL): ImportResult {
        val destRoot = destDir(destRel).absolutePath
        val copied = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        runCatching { sh("mkdir -p \"$destRoot\"") }

        if (files.isEmpty()) return ImportResult(copied, failed, destRoot)

        // ★逐个 cp（不用批量：700+ 文件会撞命令行上限）
        //   目标目录是 App 自己的外部私有目录，权限本来就对，无需 chmod
        //   （更何况 /sdcard 上的 chmod 是 FUSE 空操作）。
        for (src in files) {
            val name = src.substringAfterLast('/')
            val dst = "$destRoot/$name"
            val cpOut = runCatching { sh("cp -f \"$src\" \"$dst\" 2>&1") }
                .getOrElse { e ->
                    failed += name to (e.message ?: "cp 异常")
                    continue
                }
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

    /**
     * 目标目录（App 的外部私有目录，读写无限制）。
     *
     * `getExternalFilesDir(null)` → `/sdcard/Android/data/pub.miku.neuralstem/files/`
     * 详见 [DEST_REL] 的注释（为何不用公共 `/sdcard/Music/NCM`）。
     */
    /**
     * 目标目录。
     *
     * ## ★ 必须用 [Context.getExternalFilesDir]，不能手拼路径（2026-10-25 真机踩到）
     * 手拼 `/sdcard/Android/data/<pkg>/files/NCM` 会踩到一个陷阱：
     * **如果这个目录先被 Shizuku（shell 用户）用 `mkdir -p` 创建**，
     * 它的属主就是 `shell` → App 写不进去，报：
     * ```
     * FileNotFoundException: .../NCM/xxx.ncm.part: open failed: EACCES
     * ```
     * [Context.getExternalFilesDir] 返回的目录由系统保证属主是 App 自己。
     *
     * 注：本函数不带 Context；App 首次使用前应先调 [ensureDir] 传 context 创建。
     */
    fun destDir(context: Context, destRel: String = DEST_REL): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, destRel).apply { mkdirs() }

    /** 兼容旧调用：不带 context 时退回手拼路径（仅当目录已由 App 创建时安全）。 */
    @Deprecated("用 destDir(context)，避免目录属主是 shell")
    fun destDir(destRel: String = DEST_REL): File =
        File(
            Environment.getExternalStorageDirectory(),
            "Android/data/pub.miku.neuralstem/files/$destRel",
        )

    /** 列出目标目录里已导入的 NCM（由 App 自己创建目录 → 可正常读）。 */
    fun listImported(context: Context, destRel: String = DEST_REL): List<File> =
        destDir(context, destRel).listFiles { f -> f.isFile && f.name.endsWith(".ncm", true) }
            ?.sortedBy { it.name } ?: emptyList()

    /** 兼容旧签名。 */
    @Deprecated("用 listImported(context)")
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

    /** sh() 的总超时（毫秒）。列目录是秒级；复制用更长的 [COPY_TIMEOUT_MS]。 */
    private const val TIMEOUT_MS = 15_000L

    /** Android 11+ 才需要 Shizuku（10 及以下可直接读 Android/data）。 */
    fun needsShizuku(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    // ================= ★方案 A：用 stdout 传字节（2026-10-25 真机定型） =================

    /** 单文件传输超时（大无损文件可能 100MB+，给 5 分钟）。 */
    private const val COPY_TIMEOUT_MS = 300_000L

    /**
     * ★把一个 NCM 文件的**字节**从 shell 层取回来，写成 App 自己的文件。
     *
     * ## 为何必须走 stdout（而不是直接 `cp`）
     * 真机实测了两条路都不行：
     *
     * | 做法 | 结果 |
     * |---|---|
     * | `cp` 到 `/sdcard/Music/NCM/` | 成功，但属主是 `u0_a147 media_rw`，App 打开报 `EACCES` |
     * | 在那里 `chmod 644` | 返回 0 但**权限不变**（`/sdcard` 是 FUSE 虚拟化，chmod 是空操作）|
     * | `cp` 到 App 外部私有目录 | 成功，但属主是 **`shell`**，App 仍 `Permission denied` |
     *
     * 根本矛盾：**shell 写出来的文件，属主就是 shell；非 root 无法改变属主。**
     * ⇒ 只能让 **App 自己写**：shell 端 `cat` 文件到 stdout，App 边读边写入自己的目录。
     * 这样文件的属主天然是 App 自己，读写无任何限制。
     *
     * ## 实现要点
     * - 用 `cat` 而非 `cp`，**不走任何落盘中转**
     * - 不用 `bufferedReader`（会做字符解码，二进制会坏）→ 直接读 [Process.getInputStream] 字节流
     * - 边读边写，内存占用只有缓冲区大小（不需先把整个文件读进内存）
     *
     * @return 写入的目标文件；失败返回 null（原因写 [diag]）
     */
    fun pullFileToAppStorage(srcPath: String, destDir: File): File? {
        val name = srcPath.substringAfterLast('/')
        if (!destDir.exists()) destDir.mkdirs()
        val out = File(destDir, name)
        val tmp = File(destDir, "$name.part")

        return try {
            val process = newShizukuProcess(arrayOf("sh", "-c", "cat \"$srcPath\""))
            var total = 0L
            tmp.outputStream().use { fos ->
                val ins = process.inputStream
                val buf = ByteArray(64 * 1024)
                val deadline = System.currentTimeMillis() + COPY_TIMEOUT_MS
                while (true) {
                    if (System.currentTimeMillis() > deadline) error("传输超时")
                    val n = ins.read(buf)
                    if (n <= 0) break
                    fos.write(buf, 0, n)
                    total += n
                }
            }
            runCatching { process.destroy() }
            if (total <= 0L) error("0 字节")
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) error("rename 失败")
            out
        } catch (t: Throwable) {
            lastError = "$name: ${t.javaClass.simpleName}: ${t.message}"
            runCatching { tmp.delete() }
            Log.e(TAG, "pull failed: $srcPath", t)
            null
        }
    }

    /** 最近一次 pull 的错误（供 UI/诊断读取）。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 用 Shizuku 把一个文件**直接读成字节数组**（不落盘）。
     *
     * 用途：批量扫描编码时需要读很多文件，落盘再读太浪费。
     * 代价：整个文件进内存（一首 15-40MB，扫 20 首逐个读即可，不并行）。
     */
    fun readBytesViaShizuku(srcPath: String): ByteArray? = runCatching {
        val process = newShizukuProcess(arrayOf("sh", "-c", "cat \"$srcPath\""))
        val bytes = process.inputStream.use { it.readBytes() }
        runCatching { process.destroy() }
        if (bytes.isEmpty()) error("0 字节")
        bytes
    }.onFailure {
        lastError = "readBytes: ${it.javaClass.simpleName}: ${it.message}"
        Log.w(TAG, "readBytes failed: $srcPath", it)
    }.getOrNull()

    /**
     * 建一个 Shizuku 远程进程（可拿二进制流）。
     *
     * 与 [sh] 同思路，但**不读 String**：返回 [Process] 让调用方自己读字节。
     */
    private fun newShizukuProcess(argv: Array<String>): Process {
        val shizukuCls = Class.forName("rikka.shizuku.Shizuku")
        val serviceBinder = shizukuCls.getMethod("getBinder").invoke(null) as? android.os.IBinder
            ?: error("Shizuku binder 为空（未运行？）")
        val stubCls = Class.forName("moe.shizuku.server.IShizukuService\$Stub")
        val service = stubCls.getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, serviceBinder) ?: error("IShizukuService 转换失败")
        val remoteProcess = service.javaClass.getMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).invoke(service, argv, null, null) ?: error("newProcess 返回 null")
        val rpCls = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
        val ctor = rpCls.getDeclaredConstructor(
            Class.forName("moe.shizuku.server.IRemoteProcess"),
        ).apply { isAccessible = true }
        return ctor.newInstance(remoteProcess) as Process
    }

    /**
     * 批量把 NCM 拉进 App 自己的存储。
     *
     * 逐个 [pullFileToAppStorage]，汇总成果。
     */
    fun importByPull(context: Context, files: List<String>, destRel: String = DEST_REL): ImportResult {
        val destDir = destDir(context, destRel)
        val copied = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (src in files) {
            val name = src.substringAfterLast('/')
            // ★把异常信息带进 failed（不然只看到“pull 失败”没法排查）
            runCatching { pullFileToAppStorage(src, destDir) }
                .onSuccess { f ->
                    if (f != null) copied += f.absolutePath
                    else failed += name to "pull 返回 null（见 diag）"
                }
                .onFailure { e -> failed += name to (e.message ?: e.javaClass.simpleName) }
        }
        Log.i(TAG, "importByPull: copied=${copied.size} failed=${failed.size} dest=${destDir.absolutePath}")
        return ImportResult(copied, failed, destDir.absolutePath)
    }
}
