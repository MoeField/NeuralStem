package pub.miku.neuralstem.settings

import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.abs

/**
 * ★模型一键下载：**下载源探测 + 文件下载**。
 *
 * ## 设计原则
 * - **探测直接用目标域名**（HEAD 各 3s），不用 ping google —— 见 [ModelSources] 注释。
 * - **下载到 `.part` 临时文件**，完成后 rename → 中途失败/取消**不会留下坏文件**。
 * - **进度按字节**（多文件累加），UI 可直接画。
 * - 整个过程跑在调用方协程 → 用户可取消。
 */
class ModelDownloader(private val filesDir: File) {

    sealed interface State {
        data object Idle : State
        data class Probing(val hint: String) : State
        data class Downloading(
            /** 已下载字节（全部文件累加）。 */
            val bytesDone: Long,
            val bytesTotal: Long,
            val currentFile: String,
        ) : State {
            val percent: Int
                get() = if (bytesTotal > 0) {
                    ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100)
                } else 0
        }
        data class Done(val baseUrl: String) : State        data class Failed(val message: String) : State
    }

    /**
     * ★每个模型包**独立**的状态。
     *
     * 为何不用单一 `StateFlow<State>`：用户可能**同时下载多个模型**，
     * 共享单值时后启动的会覆盖前面的 → UI 上两个进度条“互相跳”（真机实报）。
     */
    private val _states = MutableStateFlow<Map<ModelPack, State>>(emptyMap())
    val states: StateFlow<Map<ModelPack, State>> = _states.asStateFlow()

    /** 设置某个包的状态（线程安全地替换 map 中的一项）。 */
    @Synchronized
    private fun setState(pack: ModelPack, s: State) {
        _states.value = _states.value.toMutableMap().apply { put(pack, s) }
    }

    /** 取某个包的当前状态（无记录 = Idle）。 */
    fun stateOf(pack: ModelPack): State = _states.value[pack] ?: State.Idle

    /** 该包是否正在下载（用于 UI 禁用/展示）。 */
    fun isDownloading(pack: ModelPack): Boolean = stateOf(pack) is State.Downloading

    /** 上次探测结果（进程内缓存；设置页展示用）。 */
    @Volatile
    var lastProbedBaseUrl: String? = null
        private set

    /** 清除探测缓存（用户切换下载源后调 → 下次重新探测）。 */
    fun clearProbeCache() {
        lastProbedBaseUrl = null
    }

    /**
     * 解析下载源基础 URL。
     *
     * @param pref 用户偏好；AUTO 时并发探测 HF 与 hf-mirror，官方优先、否则用镜像。
     */
    suspend fun resolveBaseUrl(pref: ModelSourcePreference): String = withContext(Dispatchers.IO) {
        ModelSources.baseUrl(pref)?.let { return@withContext it }
        lastProbedBaseUrl?.let { return@withContext it }

        // 探测是**全局**的（与具体包无关）→ 不写进某个包的状态，
        // 避免“下载 A 时又去下载 B”把探测文案挂在 A 的进度上。
        Log.i(TAG, "probing network for model source…")
        val result = coroutineScope {
            val hf = async { probeHost(ModelSources.HUGGINGFACE) }
            val mirror = async { probeHost(ModelSources.HF_MIRROR) }
            val hfOk = hf.await()
            val mirrorOk = mirror.await()
            when {
                // 官方通 → 优先官方（速度通常更好、内容最新）
                hfOk -> ModelSources.HUGGINGFACE
                mirrorOk -> ModelSources.HF_MIRROR
                // 都不通 → 仍返回镜像（用户在国内概率大；下载时会给出明确错误）
                else -> ModelSources.HF_MIRROR
            }
        }
        lastProbedBaseUrl = result
        Log.i(TAG, "resolveBaseUrl($pref) → $result")
        result
    }

    /**
     * 拼接/改写最终下载 URL。
     *
     * 规则：
     * 1. **remotePath 是完整 URL**（UVR 的 GitHub Release 直链）→ 用它；
     *    否则拼 `base + remotePath`（HF / hf-mirror）。
     * 2. 若目标是 **github.com** 且该域名自己连不上 → 改写成代理前缀
     *    `https://api.gitproxy.dev/github.com/<path>`（Cloudflare 加速，实测可用）。
     */
    private fun resolveUrl(f: ModelFile, base: String): String {
        val raw = if (f.remotePath.startsWith("http")) f.remotePath else "$base${f.remotePath}"
        return maybeProxyGithub(raw)
    }

    /**
     * GitHub ↔ 代理前缀改写。
     *
     * ## 为何需要
     * 国内网络常见 `github.com:443` 连不上（TCP 超时），而 UVR 模型**只**在
     * GitHub Release 里（hf-mirror 镜像不了）。实测 `api.gitproxy.dev` 可用，
     * 且它做的是**前缀替换**：`api.gitproxy.dev/github.com/<path>` → `github.com/<path>`。
     *
     * ## 策略
     * - 用户显式填了代理 → 一律走代理；
     * - 否则默认走 `api.gitproxy.dev`（可在设置里关掉）。
     */
    private fun maybeProxyGithub(url: String): String {
        if (!url.startsWith("https://github.com/")) return url
        val cfg = proxyConfig()
        if (cfg.isBlank()) return url
        val rest = url.removePrefix("https://github.com/")
        return "$cfg/github.com/$rest"
    }

    /** 代理前缀（空 = 不改写，直连 GitHub）；由调用方注入（见 [proxyProvider]）。 */
    var proxyProvider: () -> String = { SettingsRepository.DEFAULT_GITHUB_PROXY }

    private fun proxyConfig(): String = proxyProvider().trimEnd('/')

    /** HEAD 探测单域名（3s 超时）。 */

    private fun probeHost(baseUrl: String): Boolean = runCatching {
        val c = (URL("$baseUrl/").openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            val code = c.responseCode
            Log.i(TAG, "probe $baseUrl → $code")
            code in 200..399
        } finally {
            c.disconnect()
        }
    }.getOrElse { e ->
        Log.i(TAG, "probe $baseUrl failed: ${e.javaClass.simpleName}")
        false
    }

    /**
     * 下载一个模型包。
     *
     * 失败/取消时**不留下坏文件**（写 `.part` 再 rename）。
     */
    suspend fun download(pack: ModelPack, pref: ModelSourcePreference) {
        val info = ModelCatalog.info(pack)
        try {
            val base = resolveBaseUrl(pref)
            val total = info.totalBytes
            var done = 0L

            for (f in info.files) {
                val dir = File(filesDir, f.targetSubdir).apply { mkdirs() }
                val target = File(dir, f.fileName)

                // 已存在且大小合理 → 跳过（已下完的文件不重下）
                // 容差同 ModelCatalog.isInstalled（fp16 头部长度会差几十字节）
                if (target.exists() && target.length() > 0L &&
                    (f.expectedBytes <= 0L ||
                        abs(target.length() - f.expectedBytes) <= 64L * 1024)
                ) {
                    done += target.length()
                    setState(pack, State.Downloading(done, total, f.fileName))
                    continue
                }

                val part = File(dir, "${f.fileName}.part")
                if (part.exists()) part.delete()

                setState(pack, State.Downloading(done, total, f.fileName))
                // ★URL 组装 + 代理改写，见 [resolveUrl]
                downloadOne(resolveUrl(f, base), part) { delta ->
                    done += delta
                    setState(pack, State.Downloading(done, total, f.fileName))
                }
                if (!part.renameTo(target)) {
                    throw IOException("无法重命名 ${part.name} → ${target.name}")
                }
            }
            setState(pack, State.Done(base))
            Log.i(TAG, "download done: $pack via $base (${ModelCatalog.humanSize(done)})")
        } catch (ce: CancellationException) {
            cleanupPartFiles(pack)
            setState(pack, State.Idle)
            throw ce
        } catch (t: Throwable) {
            cleanupPartFiles(pack)
            Log.e(TAG, "download failed: $pack", t)
            setState(pack, State.Failed(describe(t)))
        }
    }

    /**
     * 把底层异常翻译成**用户能看懂 + 可操作**的提示。
     *
     * 尤其：DNS 失败（`GaiException` / `UnknownHostException`）在用户看来就是
     * “下载失败”，但实际上可能是①无网络②域名被扰③（本项目曾经的）漏申请 INTERNET 权限。
     */
    private fun describe(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.toList()
        val msg = chain.joinToString(" ← ") { it.message ?: it.javaClass.simpleName }
        return when {
            chain.any { it is UnknownHostException } ||
                chain.any { it.javaClass.simpleName.contains("GaiException") } ->
                "无法解析域名（网络不可用或域名被屏蔽）。可尝试切换下载源，或检查手机网络。"
            chain.any { it is SocketTimeoutException } ->
                "连接超时。可尝试切换下载源（hf-mirror）。"
            msg.contains("HTTP 4") ->
                "服务器返回错误（$msg）。模型仓库可能已变更，请到设置页查看来源链接。"
            else -> msg
        }
    }

    private fun cleanupPartFiles(pack: ModelPack) {
        ModelCatalog.info(pack).files.forEach { f ->
            runCatching { File(File(filesDir, f.targetSubdir), "${f.fileName}.part").delete() }
        }
    }

    /**
     * ★删除一个模型包的所有文件（模型库页的「删除」按钮）。
     *
     * 会一并清掉 `.part` 临时文件与内存中的下载状态（回到 [State.Idle]）。
     *
     * ## 注意
     * - **不动 benchmark 缓存**：实测 RTF 按 modelId 存，删文件后仍是历史真值；
     *   下次「重新检测」只会测**已装**模型，自然收敛。主动清会丢失测速历史。
     * - 下载中不允许删除（由 UI 禁用按钮；这里再挡一次）。
     *
     * @return true = 该包已不在磁盘上（全部删除成功/本来就没装）。
     */
    @Synchronized
    fun delete(pack: ModelPack): Boolean {
        if (isDownloading(pack)) {
            Log.w(TAG, "delete($pack) 被拒：正在下载")
            return false
        }
        var ok = true
        ModelCatalog.info(pack).files.forEach { f ->
            val dir = File(filesDir, f.targetSubdir)
            val target = File(dir, f.fileName)
            if (target.exists() && !target.delete()) ok = false
            runCatching { File(dir, "${f.fileName}.part").delete() }
        }
        if (ok) {
            _states.value = _states.value.toMutableMap().apply { remove(pack) }
            Log.i(TAG, "deleted: $pack")
        } else {
            Log.w(TAG, "delete($pack) 部分文件删除失败")
        }
        return ok
    }

    /**
     * 下载单个文件到 [dest]，按块回调增量字节。
     *
     * ★必须**手动跟随重定向**：GitHub Release 的下载链接会 302 到
     * `objects.githubusercontent.com`（**跨域名**），而 Java 的
     * `instanceFollowRedirects = true` **不会跨域名跟随** → 会得到一个
     * 空 body 并卡住（实测：UVR 从 GitHub 下载永远停在 0 B）。
     */
    private suspend fun downloadOne(urlStr: String, dest: File, onDelta: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            var url = urlStr
            var conn: HttpURLConnection? = null
            try {
                // 最多跟 5 跳（GitHub 一般 1 跳：github.com → objects.githubusercontent.com）
                var hops = 0
                while (true) {
                    Log.i(TAG, "GET $url")
                    val c = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        instanceFollowRedirects = false // 手动跟，见 KDoc
                        setRequestProperty("User-Agent", "NeuralStem/Android")
                    }
                    val code = c.responseCode
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location")
                        c.disconnect()
                        if (loc.isNullOrBlank() || ++hops > 5) {
                            throw IOException("重定向失败（HTTP $code, Location=$loc）")
                        }
                        url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
                        continue
                    }
                    if (code !in 200..299) throw IOException("HTTP $code")
                    conn = c
                    break
                }
                val c = conn ?: throw IOException("no connection")
                c.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(BUF_SIZE)
                        while (true) {
                            coroutineScope { ensureActive() } // 响应取消（每块检查一次）
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            onDelta(n.toLong())
                        }
                    }
                }
            } finally {
                conn?.disconnect()
            }
        }

    private companion object {
        const val TAG = "ModelDownload"
        const val PROBE_TIMEOUT_MS = 3_000
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUF_SIZE = 1 shl 16
    }
}
