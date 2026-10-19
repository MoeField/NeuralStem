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
            val pack: ModelPack,
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
        data class Done(val pack: ModelPack, val baseUrl: String) : State
        data class Failed(val pack: ModelPack?, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

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

        _state.value = State.Probing("正在检测网络…")
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

                // 已存在且大小匹配 → 跳过（已下完的文件不重下）
                if (target.exists() && target.length() > 0L &&
                    (f.expectedBytes <= 0L || target.length() == f.expectedBytes)
                ) {
                    done += target.length()
                    _state.value = State.Downloading(pack, done, total, f.fileName)
                    continue
                }

                val part = File(dir, "${f.fileName}.part")
                if (part.exists()) part.delete()

                _state.value = State.Downloading(pack, done, total, f.fileName)
                downloadOne("$base${f.remotePath}", part) { delta ->
                    done += delta
                    _state.value = State.Downloading(pack, done, total, f.fileName)
                }
                if (!part.renameTo(target)) {
                    throw IOException("无法重命名 ${part.name} → ${target.name}")
                }
            }
            _state.value = State.Done(pack, base)
            Log.i(TAG, "download done: $pack via $base (${ModelCatalog.humanSize(done)})")
        } catch (ce: CancellationException) {
            cleanupPartFiles(pack)
            _state.value = State.Idle
            throw ce
        } catch (t: Throwable) {
            cleanupPartFiles(pack)
            Log.e(TAG, "download failed: $pack", t)
            _state.value = State.Failed(pack, describe(t))
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

    /** 下载单个文件到 [dest]，按块回调增量字节。 */
    private suspend fun downloadOne(urlStr: String, dest: File, onDelta: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "GET $urlStr")
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "NeuralStem/Android")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                conn.inputStream.use { input ->
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
                conn.disconnect()
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
