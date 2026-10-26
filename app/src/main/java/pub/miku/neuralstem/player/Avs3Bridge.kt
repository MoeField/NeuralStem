package pub.miku.neuralstem.player

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * ★`main` 源集 ↔ `ncm` 源集的**反射桥**。
 *
 * ## 为什么需要它
 * AVS3 的全部实现都在 `app/src/ncm/`（只在 `-Pwith_ncm=true` 时参与编译），
 * 而 `PlayerViewModel` 在 `main` 源集 —— **默认构建下 `ncm` 源集根本不存在**，
 * 直接 `import` 会编译失败。
 *
 * 所以走反射。这与项目里 `NcmMemoryDataSource` / `NcmImportViewHolder` 的处理方式一致。
 *
 * ## 代价与取舍
 * 反射没有编译期检查 —— 方法签名改了不会报错，只在运行时炸。
 * 所以这里每个反射点都：
 * 1. 用 `runCatching` 包住，失败时**优雅降级**（当作「不是 AVS3」或「解不了」）
 * 2. 类名/方法名集中在本文件，改的时候只需改一处
 *
 * ## 默认构建下的行为
 * - [shouldIntercept] 永远 false（类找不到）→ 走原播放路径，完全无副作用
 * - [prepare] 返回 Failed → UI 提示不可播
 */
object Avs3Bridge {

    private const val TAG = "Avs3Bridge"

    private const val CLS_COORDINATOR =
        "pub.miku.neuralstem.experimental.ncm.Avs3PlaybackCoordinator"

    private const val CLS_PROGRESS =
        "pub.miku.neuralstem.experimental.ncm.Avs3PlaybackCoordinator\$Progress"

    /** 这个构建里到底有没有 AVS3 支持。 */
    val available: Boolean by lazy {
        runCatching { Class.forName(CLS_COORDINATOR) }.isSuccess
    }

    /**
     * 把播放 uri 解析成**真实文件路径**（自动处理 `netease://` 逻辑引用）。
     *
     * ## 为何需要
     * 网易云的歌在私有目录，App 拿不到可用的 `file://`，
     * 歌单里存的是 `netease://<URL编码的绝对路径>`。
     * 播放前必须解回真实路径，才能借 Shizuku 读。
     *
     * @return 真实路径；无法解析时返回 null
     */
    fun resolvePath(uri: Uri): String? {
        if (!available) return uri.path
        return runCatching {
            val cls = Class.forName(CLS_COORDINATOR)
            val inst = cls.getField("INSTANCE").get(null)
            cls.getMethod("resolvePath", Uri::class.java)
                .invoke(inst, uri) as? String
        }.getOrElse {
            // 反射失败 → 回退到普通路径解析
            uri.path
        }
    }

    /** 这个 uri 是不是「网易云私有目录的逻辑引用」。 */
    fun isNeteaseUri(uri: Uri): Boolean = NeteaseUriCodec.isNetease(uri.toString())

    /** 这个 uri 是不是 NCM 播放源（`ncm://<key>`，解密后的字节）。 */
    fun isNcmUri(uri: Uri): Boolean = uri.scheme == "ncm"

    /** NCM 解密结果里记录的实际格式（`flac` / `mp3` / `m4a`）；没有返回 null。 */
    fun ncmFormatOf(uri: Uri): String? {
        val key = uri.host ?: return null
        return runCatching {
            val cls = Class.forName("pub.miku.neuralstem.experimental.ncm.NcmPlaybackStore")
            val inst = cls.getField("INSTANCE").get(null)
            cls.getMethod("formatOf", String::class.java).invoke(inst, key) as? String
        }.getOrNull()
    }

    /**
     * 处理 `.ncm` 源文件（解密 + 分流 + 可能的 AVS3 解码）。
     *
     * ⚠️ **阻塞调用**，必须在 `Dispatchers.IO` 里跑。
     */
    fun prepareNcm(
        app: Application,
        srcUri: Uri,
        onProgress: ((Int) -> Unit)?,
        shouldCancel: () -> Boolean,
    ): Prepared {
        val clsName = "pub.miku.neuralstem.experimental.ncm.NcmPlaybackCoordinator"
        if (runCatching { Class.forName(clsName) }.isFailure) {
            return Prepared.Failed("此构建未包含 NCM 支持")
        }
        return runCatching {
            val cls = Class.forName(clsName)
            val inst = cls.getField("INSTANCE").get(null)
            val progressCls = Class.forName("$clsName\$Progress")

            val progressProxy = onProgress?.let { cb ->
                java.lang.reflect.Proxy.newProxyInstance(
                    progressCls.classLoader,
                    arrayOf(progressCls),
                ) { _, method, args ->
                    when (method.name) {
                        "onProgress" -> {
                            (args?.getOrNull(0) as? Int)?.let(cb)
                            null
                        }
                        "hashCode" -> System.identityHashCode(progressCls)
                        "equals" -> false
                        "toString" -> "NcmProgressProxy"
                        else -> null
                    }
                }
            }

            val res = cls.getMethod(
                "prepare",
                Application::class.java,
                Uri::class.java,
                progressCls,
                kotlin.jvm.functions.Function0::class.java,
            ).invoke(
                inst, app, srcUri, progressProxy,
                object : kotlin.jvm.functions.Function0<Boolean> {
                    override fun invoke(): Boolean = shouldCancel()
                },
            ) ?: return Prepared.Failed("prepare 返回 null")

            val rc = res.javaClass
            when (rc.simpleName) {
                "Ready" -> Prepared.Ready(rc.getMethod("getUri").invoke(res) as Uri)
                "Failed" -> Prepared.Failed(
                    rc.getMethod("getMessage").invoke(res) as? String ?: "未知错误",
                )
                else -> Prepared.Failed("未知返回类型 ${rc.simpleName}")
            }
        }.getOrElse {
            Log.w(TAG, "prepareNcm 反射失败", it)
            Prepared.Failed(it.message ?: "NCM 处理失败")
        }
    }

    /**
     * 是否需要把 uri 交给 AVS3 流程处理。
     *
     * 判断依据：文件是 `.m4a`，且内部 codec 是 `av3a`。
     * 注意**不能只看扩展名** —— 实测 220 个 m4a 里 219 个是 av3a，
     * 但也有真 AAC 的 m4a，那些应该走原生路径（更快、无需解码）。
     */
    fun shouldIntercept(context: Context, uri: Uri): Boolean {
        if (!available) return false
        // 先按名字快速筛（非 .m4a 直接 false，省一次 IO）
        val name = uri.lastPathSegment ?: uri.toString()
        if (!name.lowercase().endsWith(".m4a")) return false
        return runCatching {
            val cls = Class.forName(CLS_COORDINATOR)
            val inst = cls.getField("INSTANCE").get(null)
            cls.getMethod("isAvs3Source", Context::class.java, Uri::class.java)
                .invoke(inst, context, uri) as? Boolean ?: false
        }.onFailure {
            Log.w(TAG, "isAvs3Source 反射失败", it)
        }.getOrDefault(false)
    }

    /** 转换结果（与 ncm 侧 `Avs3PlaybackCoordinator.Prepared` 一一对应）。 */
    sealed interface Prepared {
        data class Ready(val uri: Uri) : Prepared
        data class Failed(val message: String) : Prepared
    }

    /**
     * 把源 uri 转成可播 uri（缓存命中直接返回；否则解码 + 写缓存）。
     *
     * ⚠️ **阻塞调用**（解码要几十秒），必须在 `Dispatchers.IO` 里跑。
     *
     * @param onProgress (标识, 0..100)。标识用的是**源 uri 字符串**，
     *        与 ncm 侧 `Progress.onProgress(uri: String, ...)` 的约定一致
     *        （那边用 uri 当 key，因为缓存键由 uri 算）。
     * @param shouldCancel 每帧询问一次，返回 true 就中止
     */
    fun prepare(
        app: Application,
        srcUri: Uri,
        onProgress: ((String, Int) -> Unit)?,
        shouldCancel: () -> Boolean,
    ): Prepared {
        if (!available) return Prepared.Failed("此构建未包含 AVS3 解码器")

        return runCatching {
            val cls = Class.forName(CLS_COORDINATOR)
            val inst = cls.getField("INSTANCE").get(null)
            val progressCls = Class.forName(CLS_PROGRESS)

            // Progress 是 fun interface → 用动态代理实现它
            val progressProxy = onProgress?.let { cb ->
                java.lang.reflect.Proxy.newProxyInstance(
                    progressCls.classLoader,
                    arrayOf(progressCls),
                ) { _, method, args ->
                    when (method.name) {
                        "onProgress" -> {
                            val id = args?.getOrNull(0) as? String
                            val pct = args?.getOrNull(1) as? Int
                            if (id != null && pct != null) cb(id, pct)
                            null
                        }
                        "hashCode" -> System.identityHashCode(progressCls)
                        "equals" -> false
                        "toString" -> "Avs3ProgressProxy"
                        else -> null
                    }
                }
            }

            val res = cls.getMethod(
                "prepare",
                Application::class.java,
                Uri::class.java,
                progressCls,
                kotlin.jvm.functions.Function0::class.java,
            ).invoke(
                inst, app, srcUri, progressProxy,
                object : kotlin.jvm.functions.Function0<Boolean> {
                    override fun invoke(): Boolean = shouldCancel()
                },
            ) ?: return Prepared.Failed("prepare 返回 null")

            // 解包 Prepared.Ready / Failed
            val rc = res.javaClass
            when (rc.simpleName) {
                "Ready" -> Prepared.Ready(rc.getMethod("getUri").invoke(res) as Uri)
                "Failed" -> Prepared.Failed(
                    rc.getMethod("getMessage").invoke(res) as? String ?: "未知错误",
                )
                else -> Prepared.Failed("未知返回类型 ${rc.simpleName}")
            }
        }.getOrElse {
            Log.w(TAG, "prepare 反射失败", it)
            Prepared.Failed(it.message ?: "解码失败")
        }
    }
}
